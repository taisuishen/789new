package com.bingo789.game.adapter.jdb;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.ProviderAdapter;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.RoundStatus;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.adapter.support.Ciphers;
import com.bingo789.game.adapter.support.Fields;
import com.bingo789.game.adapter.support.Forms;
import com.bingo789.game.api.dto.BetPullPage;
import com.bingo789.game.api.dto.BetPullQuery;
import com.bingo789.game.api.dto.LaunchCommand;
import com.bingo789.game.api.dto.LaunchView;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.bingo789.game.api.dto.ProviderGameView;
import com.bingo789.game.provider.ProviderClient;
import com.bingo789.game.wallet.PlayerIds;
import com.bingo789.wallet.api.enums.TxnType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JDB seamless wallet: every call is {@code POST /callback/JDB} (application/x-www-form-urlencoded, one field
 * {@code x}); the action is inside the encrypted JSON ({@code 6} balance, {@code 8} bet-and-settle, {@code 4} cancel
 * bet-and-settle, {@code 9} bet, {@code 10} settle, {@code 11} cancel bet, {@code 13} withdraw into a fish / arcade
 * game, {@code 14} deposit back, {@code 15} cancel withdraw). Replies are JSON {@code {"status","err_text","balance"}},
 * always HTTP 200; amounts are decimals in currency units.
 * <p>
 * Auth: {@code x = Base64URL(AES/CBC/NoPadding(json right-padded with spaces, key, iv))}. There is no signature: the body
 * is authenticated by decrypting it in {@link #parse} (strict UTF-8, must be a JSON object); verifySignature is empty.
 * {@code ts} is checked on 6 / 8 / 9 / 13 only: credits and reversals are exempt because JDB retries them with the
 * original payload until acknowledged (replays are harmless, every money call is idempotent).
 * <p>
 * Idempotency: {@code transferId} identifies every call. 8 is bet + win keyed on the same transferId (the TxnType keeps
 * them apart) in round {@code historyId}; 4 reverses that bet and its payout; 9 is a bet of {@code historyId}; 10 pays
 * {@code amount} once, keyed on the settle's transferId, checked against the first {@code refTransferIds} bet; 11
 * reverses each referenced bet; 13 is a bet in its own round (its transferId), 14 pays the returned amount into the
 * round of its first referenced withdraw, 15 reverses the withdraw. Duplicates answer {@code 0000} with the balance.
 * <p>
 * Deliberate deviations from the old code: duplicates of 8 / 10 / 11 answer 0000 (old 6008); 4 reverses stake AND win
 * (old: only the win when both existed); settle and cancel find the bet through the wallet (old: 1 h Redis marker /
 * 2 h ES window); a settle with a negative amount answers 6007 (old: 0000 without money) and one without references is
 * checked against its round; 11 of an unknown bet answers 0000 and leaves a tombstone (old 6009); a deposit without
 * {@code refTransferIds} answers 6007 (old: 0000 without crediting); the balance is always returned on success; no
 * per-player Redis lock (the wallet row lock serializes a player). JDB currency codes are mapped (PP = PHP, US = USD
 * ...); the thousand-unit VN / RP are not supported. Bet pull splits windows into 15-minute (action 29) and 5-minute
 * (action 64, older than 2 h) requests as JDB requires (old: one request per window).
 * <p>
 * Configuration ({@code bingo.providers.JDB}): {@code operator-id} = parent (agent account), {@code secret} = AES key,
 * {@code secrets.iv} = AES IV, {@code settings.dc} = domain code, {@code base-url} = JDB API URL. Bet pull:
 * {@code settle-delay: 3m} (JDB serves records up to now - 3 minutes); max-window and page-interval defaults fit.
 */
@Slf4j
@Component
public class JdbAdapter implements ProviderAdapter {

    static final String NAME = "JDB";

    private static final ZoneOffset JDB_ZONE = ZoneOffset.ofHours(-4);
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:00");
    private static final DateTimeFormatter RECORD_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    static final Duration RECENT_MAX = Duration.ofMinutes(15);
    static final Duration HISTORY_MAX = Duration.ofMinutes(5);
    /** Records older than this are only served by action 64. */
    static final Duration RECENT = Duration.ofHours(2);
    private static final String SUCCESS = "0000";
    /** Calls that only read or take money: a stale ts is refused. */
    private static final Set<String> FRESH_ONLY = Set.of("6", "8", "9", "13");
    /** JDB codes of the 1:1 currencies; any other code passes through unchanged (and is refused unless configured). */
    private static final Map<String, String> CURRENCIES = Map.of("PP", "PHP", "US", "USD", "EU", "EUR", "RB", "CNY",
            "TB", "THB", "JP", "JPY", "KW", "KRW");
    private static final Map<String, String> LANGUAGES = Map.of("zh", "cn", "th", "th", "vi", "vn", "id", "id",
            "ko", "kor", "ja", "jpn", "en", "en");

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    /** Encrypted body: authenticated by decrypting it in {@link #parse}. */
    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        JsonNode b = decrypt(request, client);
        String action = text(b, "action");
        if (action == null) {
            throw CallbackException.unknownAction("(none)");
        }
        if (FRESH_ONLY.contains(action)) {
            Fields.checkTimestamp(Instant.ofEpochMilli(Fields.longValue(text(b, "ts"), "ts")), request, client);
        }
        String uid = Fields.required(text(b, "uid"), "uid");
        String currency = currency(text(b, "currency"));
        String game = gameCode(b);
        return switch (action) {
            case "6" -> new WalletCommand.GetBalance(uid, currency);
            case "8" -> {
                String transfer = transferId(b);
                // bet is sent negative
                yield new WalletCommand.BetAndPayout(uid, currency, transfer, transfer,
                        Fields.required(text(b, "historyId"), "historyId"), game, Fields.amount(text(b, "bet"), "bet").abs(),
                        nonNegative(text(b, "win"), "win"), true);
            }
            case "4" -> {
                String transfer = transferId(b);
                yield new WalletCommand.Batch(uid, currency, List.of(
                        new WalletCommand.Rollback(uid, currency, "cancel:" + transfer, transfer, TxnType.PAYOUT, null, null),
                        new WalletCommand.Rollback(uid, currency, "cancel:" + transfer, transfer, TxnType.BET, null, null)));
            }
            case "9" -> {
                String transfer = transferId(b);
                String round = text(b, "historyId") != null ? text(b, "historyId") : text(b, "gameRoundSeqNo");
                yield new WalletCommand.Bet(uid, currency, transfer, round != null ? round : transfer, game,
                        nonNegative(text(b, "amount"), "amount"), false);
            }
            case "10" -> settle(b, uid, currency, game);
            case "11" -> {
                String transfer = transferId(b);
                List<WalletCommand> steps = new ArrayList<>();
                for (String ref : references(b)) {
                    steps.add(new WalletCommand.Rollback(uid, currency, transfer, ref, TxnType.BET, null, null));
                }
                yield steps.size() == 1 ? steps.getFirst() : new WalletCommand.Batch(uid, currency, steps);
            }
            case "13" -> {
                String transfer = transferId(b);
                yield new WalletCommand.Bet(uid, currency, transfer, transfer, game, nonNegative(text(b, "amount"), "amount"), false);
            }
            case "14" -> {
                // the round is the first withdraw's, so the payout finds its bet
                String withdraw = references(b).getFirst();
                yield new WalletCommand.Payout(uid, currency, transferId(b), withdraw, game,
                        nonNegative(text(b, "amount"), "amount"), TxnType.PAYOUT, withdraw, true);
            }
            case "15" -> new WalletCommand.Rollback(uid, currency, transferId(b),
                    Fields.required(text(b, "refTransferId"), "refTransferId"), TxnType.BET, null, null);
            default -> throw CallbackException.unknownAction(action);
        };
    }

    /** The total payout of the referenced bets, once, keyed on the settle's own transferId. */
    private static WalletCommand settle(JsonNode b, String uid, String currency, String game) {
        List<String> refs = ids(b, "refTransferIds");
        String round = text(b, "historyId");
        if (round == null && refs.isEmpty()) {
            throw CallbackException.badRequest("historyId or refTransferIds is required");
        }
        return new WalletCommand.Payout(uid, currency, transferId(b), round != null ? round : refs.getFirst(), game,
                nonNegative(text(b, "amount"), "amount"), TxnType.PAYOUT, refs.isEmpty() ? null : refs.getFirst(), true);
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        boolean reversal = command instanceof WalletCommand.Rollback
                || command instanceof WalletCommand.Batch batch && batch.steps().getFirst() instanceof WalletCommand.Rollback;
        // a cancel with nothing (more) to reverse is done: the bet never arrived (tombstoned) or had no payout
        if (outcome.isSuccess() || reversal && outcome.code() == CommandOutcome.Code.TXN_NOT_FOUND) {
            String balance = Fields.balance(outcome.balance(), client.config().balanceScale());
            return reply(SUCCESS, "", balance == null ? null : new BigDecimal(balance));
        }
        return switch (outcome.code()) {
            case INSUFFICIENT_FUNDS, PLAYER_LOCKED -> reply("6006", "Insufficient balance", null);
            // the cancel of a bet-and-settle (4: reversal of a bet and its payout) has its own failure code
            case PLAYER_NOT_FOUND -> command instanceof WalletCommand.Batch cancel && cancel.steps().stream()
                    .anyMatch(s -> s instanceof WalletCommand.Rollback r && r.targetType() == TxnType.PAYOUT)
                    ? reply("6101", "Can not cancel", null) : reply("0001", "User not exist", null);
            case BET_NOT_FOUND, TXN_NOT_FOUND -> reply("6009", "Bet order not exist", null);
            case TXN_CANCELLED, BET_SETTLED -> reply("6101", "Transaction cancelled", null);
            case INVALID_TOKEN, INVALID_REQUEST, SUCCESS -> reply("6007", "Parameter error", null);
        };
    }

    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        return switch (error) {
            case AUTH_FAILED -> reply("401", "Incorrect appSecret", null);
            case UNKNOWN_ACTION -> reply("400", "Bad Request", null);
            case BAD_REQUEST -> reply("6007", "Parameter error", null);
            // JDB retries on 9017
            case RATE_LIMITED, SYSTEM_RETRYABLE -> reply("9017", "Work in process, please try again", null);
        };
    }

    // ================================================================ outbound

    /** Action 21: single-wallet login, straight into the game for a {@code gType_mType} code, else the lobby. */
    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        if (c.demo()) {
            throw new IllegalArgumentException("JDB seamless launch has no demo mode");
        }
        Map<String, Object> params = request(21, client);
        params.put("uid", playerId);
        params.put("lang", language(c.language()));
        String[] game = c.gameCode() == null ? new String[0] : c.gameCode().split("_", 2);
        if (game.length == 2) {
            params.put("gType", game[0]);
            params.put("mType", game[1]);
            params.put("windowMode", "2");
        } else {
            params.put("windowMode", "1");
        }
        params.put("isAPP", false);
        JsonNode response = call(client, params);
        requireOk(response, "login");
        return new LaunchView(response.path("path").asString(), null);
    }

    /** Action 49: {@code data[] = {gType, list[] = {mType, name, image}}}; game code {@code gType_mType}. */
    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        JsonNode response = call(client, request(49, client));
        requireOk(response, "game list");
        return parseGames(response, client.providerCode());
    }

    static List<ProviderGameView> parseGames(JsonNode response, String providerCode) {
        List<ProviderGameView> games = new ArrayList<>();
        for (JsonNode type : response.path("data")) {
            String gType = text(type, "gType");
            for (JsonNode g : type.path("list")) {
                String code = gType + "_" + text(g, "mType");
                String name = text(g, "name") != null ? text(g, "name") : text(g, "gameName");
                games.add(new ProviderGameView(providerCode, code, name != null ? name : code, gType, null,
                        text(g, "image"), true, true));
            }
        }
        return games;
    }

    /**
     * Action 29 (last 2 h, up to 15 minutes per request) or 64 (older, up to 5 minutes), at minute granularity in JDB's
     * GMT-4 wall clock. Cursor = epoch millis of the next window start.
     */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        Instant start = query.cursor() == null ? query.from() : Instant.ofEpochMilli(Long.parseLong(query.cursor()));
        Window window = window(start, query.to(), Instant.now());
        if (window == null) {
            return new BetPullPage(List.of(), null, false);
        }
        Map<String, Object> params = request(window.history() ? 64 : 29, client);
        params.put("starttime", queryTime(window.start()));
        params.put("endtime", queryTime(window.end()));
        JsonNode response = call(client, params);
        String status = text(response, "status");
        if (status != null && !SUCCESS.equals(status)) {
            throw new IllegalStateException("JDB bet records failed: " + status + " " + text(response, "err_text"));
        }
        List<ProviderBetRecordView> records = parseRecords(response, client.providerCode());
        boolean more = window.end().isBefore(query.to().truncatedTo(ChronoUnit.MINUTES));
        return new BetPullPage(records, more ? String.valueOf(window.end().toEpochMilli()) : null, more);
    }

    /**
     * Minute-aligned {@code [start, end)}; never crosses the 2 h boundary (also a minute, so consecutive pages agree on
     * it). Null when no whole minute is left before {@code to}; its tail is read by the next, overlapping run.
     */
    record Window(Instant start, Instant end, boolean history) {
    }

    static Window window(Instant from, Instant to, Instant now) {
        Instant start = from.truncatedTo(ChronoUnit.MINUTES);
        Instant limit = to.truncatedTo(ChronoUnit.MINUTES);
        if (!limit.isAfter(start)) {
            return null;
        }
        Instant boundary = now.minus(RECENT).truncatedTo(ChronoUnit.MINUTES);
        boolean history = start.isBefore(boundary);
        Instant end = start.plus(history ? HISTORY_MAX : RECENT_MAX);
        if (end.isAfter(limit)) {
            end = limit;
        }
        if (history && end.isAfter(boundary)) {
            end = boundary;
        }
        return new Window(start, end, history);
    }

    /** bet = |bet| (|gambleBet| for gamble rounds), payout = |bet| + total (net win / loss); all rows are settled. */
    static List<ProviderBetRecordView> parseRecords(JsonNode response, String providerCode) {
        List<ProviderBetRecordView> records = new ArrayList<>();
        for (JsonNode r : response.path("data")) {
            String historyId = text(r, "historyId");
            Long userId = PlayerIds.tryDecode(text(r, "playerId"));
            if (userId == null) {
                log.warn("JDB record {} belongs to unknown player {}", historyId, text(r, "playerId"));
                continue;
            }
            BigDecimal bet = decimalOrZero(r, "bet").abs();
            BigDecimal stake = "1".equals(text(r, "hasGamble")) ? decimalOrZero(r, "gambleBet").abs() : bet;
            records.add(new ProviderBetRecordView(providerCode, historyId, historyId, userId, currency(text(r, "currency")),
                    gameCode(r), stake, bet.add(decimalOrZero(r, "total")), "SETTLED",
                    recordTime(text(r, "gameDate")), recordTime(text(r, "lastModifyTime"))));
        }
        return records;
    }

    /** No round query is used: open rounds are closed by settle / cancel, or escalated by the resolver. */
    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    /** The decrypted payload; anything that does not decrypt to a JSON object is an authentication failure. */
    static JsonNode decrypt(CallbackRequest request, ProviderClient client) {
        String x = Forms.body(request).get("x");
        if (x == null || x.isBlank()) {
            throw CallbackException.auth("missing x");
        }
        String json = open(x, client.secret(), client.secret("iv"));
        if (!json.startsWith("{")) {
            throw CallbackException.auth("x does not decrypt to JSON");
        }
        try {
            return JsonUtils.mapper().readTree(json);
        } catch (JacksonException e) {
            throw CallbackException.auth("x does not decrypt to JSON");
        }
    }

    static String open(String x, String key, String iv) {
        try {
            byte[] sealed = Base64.getUrlDecoder().decode(x.strip());
            byte[] plain = Ciphers.aesDecrypt(sealed, Ciphers.utf8(key), Ciphers.utf8(iv), Ciphers.Padding.SPACE);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plain))
                    .toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw CallbackException.auth("x does not decrypt");
        }
    }

    static String seal(String json, String key, String iv) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Ciphers.aesEncrypt(Ciphers.utf8(json), Ciphers.utf8(key), Ciphers.utf8(iv), Ciphers.Padding.SPACE));
    }

    private static Map<String, Object> request(int action, ProviderClient client) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", action);
        params.put("ts", System.currentTimeMillis());
        params.put("parent", client.config().operatorId());
        return params;
    }

    /** Outbound call: form {@code dc, x} to {@code /apiRequest.do}. */
    private static JsonNode call(ProviderClient client, Map<String, Object> params) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("dc", client.setting("dc"));
        form.put("x", seal(JsonUtils.toJson(params), client.secret(), client.secret("iv")));
        String response = client.rest().post()
                .uri("/apiRequest.do")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Forms.encode(form))
                .retrieve()
                .body(String.class);
        return JsonUtils.mapper().readTree(response);
    }

    private static void requireOk(JsonNode response, String call) {
        if (!SUCCESS.equals(text(response, "status"))) {
            throw new IllegalStateException("JDB " + call + " failed: " + text(response, "status") + " " + text(response, "err_text"));
        }
    }

    private static String transferId(JsonNode b) {
        return Fields.required(text(b, "transferId"), "transferId");
    }

    private static List<String> references(JsonNode b) {
        List<String> refs = ids(b, "refTransferIds");
        if (refs.isEmpty()) {
            throw CallbackException.badRequest("refTransferIds is required");
        }
        return refs;
    }

    private static List<String> ids(JsonNode b, String field) {
        List<String> ids = new ArrayList<>();
        for (JsonNode id : b.path(field)) {
            if (id.isValueNode() && !id.isNull() && !id.asString().isBlank()) {
                ids.add(id.asString());
            }
        }
        return ids;
    }

    private static String gameCode(JsonNode node) {
        String gType = text(node, "gType");
        String mType = text(node, "mType");
        return gType == null || mType == null ? null : gType + "_" + mType;
    }

    static String currency(String jdbCode) {
        return jdbCode == null ? null : CURRENCIES.getOrDefault(jdbCode, jdbCode);
    }

    static String language(String language) {
        if (language == null || language.length() < 2) {
            return "en";
        }
        return LANGUAGES.getOrDefault(language.substring(0, 2).toLowerCase(Locale.ROOT), "en");
    }

    /** JDB's GMT-4 wall clock, minute granularity. */
    static String queryTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, JDB_ZONE).format(QUERY_TIME);
    }

    private static Instant recordTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value.strip(), RECORD_TIME).toInstant(JDB_ZONE);
    }

    /** Text of a scalar field (numbers as written), null when absent or JSON null. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isValueNode() ? value.asString() : value.toString();
    }

    private static BigDecimal decimalOrZero(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.strip());
    }

    private static BigDecimal nonNegative(String value, String field) {
        BigDecimal amount = Fields.amount(value, field);
        if (amount.signum() < 0) {
            throw CallbackException.badRequest(field + " must not be negative");
        }
        return amount;
    }

    private static CallbackResponse reply(String status, String errText, BigDecimal balance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("err_text", errText);
        body.put("balance", balance);
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }
}
