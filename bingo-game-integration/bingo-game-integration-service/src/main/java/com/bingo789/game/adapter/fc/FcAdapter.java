package com.bingo789.game.adapter.fc;

import com.bingo789.common.core.crypto.Hmacs;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * FC (Fa Chai) seamless wallet: {@code POST /callback/FC/{GetBalance|BetNInfo|CancelBetNInfo|Bet|Settle|CancelBet|
 * EventSettle|FreeSpinBetNInfo}}, application/x-www-form-urlencoded {@code AgentCode, Currency, Params, Sign}; replies
 * JSON {@code {"Result":0,"MainPoints":..}} or {@code {"Result":code,"ErrorText":..}}, always HTTP 200; amounts are
 * decimals in currency units.
 * <p>
 * Auth: {@code Params = Base64(AES/ECB/PKCS5(json, agentKey))}, {@code Sign = md5hex(json)}. The body is authenticated
 * by decrypting it in {@link #parse} ({@code AgentCode} must be ours, the plaintext must be strict UTF-8 JSON and match
 * {@code Sign}); verifySignature is empty. {@code Ts} is checked on GetBalance / Bet / BetNInfo only: credits and
 * reversals are exempt because FC retries them with the original payload until acknowledged (replays are harmless, every
 * money call is idempotent).
 * <p>
 * Idempotency: BetNInfo is bet + win in one call, both keyed on {@code BankID} (the TxnType keeps them apart); Bet is
 * keyed on {@code BetID} and Settle pays each {@code SettleBetIDs[]} entry keyed on its betID, plus the top-level
 * {@code Refund} as payout {@code refund:<BankID>}; CancelBetNInfo / CancelBet reverse the stake and the win of that id
 * (batch: rollback of the bet, then of its payout); EventSettle pays each list item (promo, keyed on trsID); a
 * FreeSpinBetNInfo win is a FREE_PAYOUT keyed on RecordID. Rounds are {@code RecordID}. Duplicates answer
 * {@code Result 0} with the balance.
 * <p>
 * Deliberate deviations from the old code: duplicates answer success (old: 999 / 799 "Duplicate"); Settle checks the
 * bets through the wallet (old: a 1 h Redis marker, so a late settle failed) and a partly applied settle completes on
 * retry; the Settle {@code Refund} is credited (old ignored it - to confirm with FC that the per-bet win excludes it);
 * CancelBetNInfo claws back the win as well and an unknown BankID answers 221 and leaves a tombstone, so a late
 * BetNInfo is refused; a CancelBet of an unknown or unsettled BetID answers 0 (the stake, if any, is back; old 999)
 * and the "Bet amount mismatch" check is gone (the wallet reverses what it recorded); EventSettle and
 * FreeSpinBetNInfo now credit (old stubs answered 0 without paying); AgentCode must match (no fallback to the first
 * config); balances are rounded DOWN at {@code balance-scale} (old HALF_UP); no 2 h lookback windows. Not supported:
 * an EventSettle list spanning several players (answered 999: a batch is one player's) and FC's thousand-unit
 * currencies (VND / IDR amounts would need a unit factor).
 * <p>
 * Configuration ({@code bingo.providers.FC}): {@code operator-id} = AgentCode, {@code secret} = AgentKey (AES key,
 * 16/24/32 bytes), {@code base-url} = FC API URL, {@code currencies} (the bet pull runs once per currency; launch uses
 * the player's). Bet pull: {@code max-window: 15m} (FC answers at most 15 minutes per request; longer windows are split
 * into more pages), {@code page-interval: 1s} (FC asks to space history requests); settle-delay default 2m fits.
 */
@Slf4j
@Component
public class FcAdapter implements ProviderAdapter {

    static final String NAME = "FC";

    private static final ZoneOffset FC_ZONE = ZoneOffset.ofHours(-4);
    private static final DateTimeFormatter FC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Longest window FC answers in one request. */
    static final Duration MAX_QUERY = Duration.ofMinutes(15);
    /** Records older than this are only served by GetHistoryRecordList. */
    static final Duration RECENT = Duration.ofHours(2);
    /** Calls that only read or take money: a stale Ts is refused. */
    private static final Set<String> FRESH_ONLY = Set.of("GetBalance", "Bet", "BetNInfo");
    private static final Map<String, String> LANGUAGES = Map.of("en", "1", "zh", "2", "vi", "3", "th", "4", "id", "5",
            "ja", "7", "ko", "8");

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
        String action = request.action();
        if (FRESH_ONLY.contains(action)) {
            checkTs(b, request, client);
        }
        String currency = text(b, "Currency");
        String game = text(b, "GameID");
        return switch (action) {
            case "GetBalance" -> new WalletCommand.GetBalance(member(b), currency);
            case "BetNInfo" -> {
                String bank = Fields.required(text(b, "BankID"), "BankID");
                yield new WalletCommand.BetAndPayout(member(b), currency, bank, bank, Fields.required(text(b, "RecordID"), "RecordID"),
                        game, nonNegative(text(b, "Bet"), "Bet"), nonNegative(text(b, "Win"), "Win"), true);
            }
            case "CancelBetNInfo" -> reverse(member(b), currency, Fields.required(text(b, "BankID"), "BankID"), null);
            case "Bet" -> new WalletCommand.Bet(member(b), currency, Fields.required(text(b, "BetID"), "BetID"),
                    Fields.required(text(b, "RecordID"), "RecordID"), game, nonNegative(text(b, "Bet"), "Bet"), false);
            case "Settle" -> settle(b, member(b), currency, game);
            case "CancelBet" -> reverse(member(b), currency, Fields.required(text(b, "BetID"), "BetID"), text(b, "RecordID"));
            case "EventSettle" -> eventSettle(b, currency);
            case "FreeSpinBetNInfo" -> {
                String record = Fields.required(text(b, "RecordID"), "RecordID");
                yield new WalletCommand.Payout(member(b), currency, record, record, game, nonNegative(text(b, "Win"), "Win"),
                        TxnType.FREE_PAYOUT, null, true);
            }
            default -> throw CallbackException.unknownAction(action);
        };
    }

    /** One payout per settled bet (keyed on its betID), then the returned amount; the last step closes the round. */
    private static WalletCommand settle(JsonNode b, String member, String currency, String game) {
        String record = Fields.required(text(b, "RecordID"), "RecordID");
        JsonNode bets = field(b, "SettleBetIDs");
        if (bets == null || !bets.isArray() || bets.isEmpty()) {
            throw CallbackException.badRequest("SettleBetIDs is required");
        }
        List<WalletCommand.Payout> payouts = new ArrayList<>();
        for (JsonNode bet : bets) {
            String betId = Fields.required(text(bet, "betID"), "SettleBetIDs.betID");
            String win = text(bet, "win");
            payouts.add(new WalletCommand.Payout(member, currency, betId, record, game,
                    win == null ? BigDecimal.ZERO : nonNegative(win, "SettleBetIDs.win"), TxnType.PAYOUT, betId, false));
        }
        String refund = text(b, "Refund");
        if (refund != null && nonNegative(refund, "Refund").signum() > 0) {
            payouts.add(new WalletCommand.Payout(member, currency, "refund:" + Fields.required(text(b, "BankID"), "BankID"),
                    record, game, new BigDecimal(refund.strip()), TxnType.PAYOUT, null, false));
        }
        WalletCommand.Payout last = payouts.removeLast();
        List<WalletCommand> steps = new ArrayList<>(payouts);
        steps.add(new WalletCommand.Payout(last.playerId(), last.currency(), last.txnId(), last.roundId(), last.gameCode(),
                last.amount(), last.payoutType(), last.betTxnId(), true));
        return steps.size() == 1 ? steps.getFirst() : new WalletCommand.Batch(member, currency, steps);
    }

    /** Reverses the bet with that id and, when it was settled, its payout (same id, type PAYOUT). */
    private static WalletCommand reverse(String member, String currency, String txnId, String round) {
        return new WalletCommand.Batch(member, currency, List.of(
                new WalletCommand.Rollback(member, currency, "cancel:" + txnId, txnId, TxnType.PAYOUT, round, null),
                new WalletCommand.Rollback(member, currency, "cancel:" + txnId, txnId, TxnType.BET, round, null)));
    }

    /** Promotion prizes; every item must belong to the same player (a batch is one player's). */
    private static WalletCommand eventSettle(JsonNode b, String currency) {
        JsonNode items = field(b, "List");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw CallbackException.badRequest("List is required");
        }
        String member = null;
        List<WalletCommand> steps = new ArrayList<>();
        for (JsonNode item : items) {
            String account = Fields.required(text(item, "memberAccount"), "List.memberAccount");
            if (member != null && !member.equals(account)) {
                throw CallbackException.badRequest("EventSettle for several players is not supported");
            }
            member = account;
            String txn = text(item, "trsID") != null ? text(item, "trsID") : text(item, "bankID");
            steps.add(new WalletCommand.Payout(account, currency, Fields.required(txn, "List.trsID"),
                    "promo:" + Fields.required(text(item, "eventID"), "List.eventID"), text(item, "gameID"),
                    nonNegative(text(item, "points"), "List.points"), TxnType.PROMO_PAYOUT, null, true));
        }
        return steps.size() == 1 ? steps.getFirst() : new WalletCommand.Batch(member, currency, steps);
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        // CancelBet of a bet that was never settled: the stake is back, there is no win to reverse
        boolean unsettledCancel = outcome.code() == CommandOutcome.Code.TXN_NOT_FOUND && "CancelBet".equals(request.action());
        if (outcome.isSuccess() || unsettledCancel) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Result", 0);
            String balance = Fields.balance(outcome.balance(), client.config().balanceScale());
            body.put("MainPoints", balance == null ? BigDecimal.ZERO : new BigDecimal(balance));
            return CallbackResponse.json(200, JsonUtils.toJson(body));
        }
        return switch (outcome.code()) {
            case INSUFFICIENT_FUNDS, PLAYER_LOCKED -> error(203, "Insufficient balance");
            case PLAYER_NOT_FOUND -> error(500, "Account does not exist");
            // settle before its bet (FC retries), or CancelBetNInfo of an unknown BankID
            case BET_NOT_FOUND, TXN_NOT_FOUND -> error(221, "Transaction not found");
            case TXN_CANCELLED, BET_SETTLED -> error(999, "Transaction cancelled");
            case INVALID_TOKEN, INVALID_REQUEST, SUCCESS -> error(999, "Invalid request");
        };
    }

    /** FC has one error code for everything else; 999 is never success, so FC retries or cancels. */
    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        return switch (error) {
            case AUTH_FAILED -> error(999, "Invalid signature");
            case BAD_REQUEST, UNKNOWN_ACTION -> error(999, "Invalid parameters");
            case RATE_LIMITED, SYSTEM_RETRYABLE -> error(999, "System busy, please retry");
        };
    }

    // ================================================================ outbound

    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        if (c.demo()) {
            throw new IllegalArgumentException("FC seamless launch has no demo mode");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("MemberAccount", playerId);
        params.put("LanguageID", language(c.language()));
        if (c.gameCode() == null || c.gameCode().isBlank() || c.gameCode().equalsIgnoreCase(NAME)) {
            params.put("LoginGameHall", true);
        } else {
            params.put("GameID", c.gameCode());
            params.put("LoginGameHall", false);
        }
        return new LaunchView(call(client, "/Login", c.currency(), params).path("Url").asString(), null);
    }

    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        List<String> currencies = client.config().currencies();
        JsonNode response = call(client, "/GetGameIconList", currencies.isEmpty() ? null : currencies.getFirst(), Map.of());
        return parseGames(response, client.providerCode());
    }

    /** {@code GetGameIconList: {<category>: {<gameId>: {gameNameOfEnglish, enPng ...}}}}. */
    static List<ProviderGameView> parseGames(JsonNode response, String providerCode) {
        List<ProviderGameView> games = new ArrayList<>();
        for (Map.Entry<String, JsonNode> category : response.path("GetGameIconList").properties()) {
            for (Map.Entry<String, JsonNode> game : category.getValue().properties()) {
                JsonNode g = game.getValue();
                String name = text(g, "gameNameOfEnglish") != null ? text(g, "gameNameOfEnglish") : text(g, "gameName");
                games.add(new ProviderGameView(providerCode, game.getKey(), name != null ? name : game.getKey(),
                        category.getKey(), null, text(g, "enPng"), true, true));
            }
        }
        return games;
    }

    /**
     * GetRecordList / GetHistoryRecordList, one request per configured currency and window of at most 15 minutes.
     * Cursor {@code <currency index>:<epoch millis of the next window start>}.
     */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        List<String> currencies = client.config().currencies();
        if (currencies.isEmpty()) {
            throw new IllegalStateException("FC bet pull needs bingo.providers." + client.providerCode() + ".currencies");
        }
        int currency = 0;
        Instant start = query.from();
        if (query.cursor() != null) {
            String[] parts = query.cursor().split(":");
            currency = Integer.parseInt(parts[0]);
            start = Instant.ofEpochMilli(Long.parseLong(parts[1]));
        }
        Window window = window(start, query.to(), Instant.now());
        List<ProviderBetRecordView> records = List.of();
        if (window.requested()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("StartDate", fcTime(window.first()));
            params.put("EndDate", fcTime(window.last()));
            JsonNode response = call(client, window.history() ? "/GetHistoryRecordList" : "/GetRecordList",
                    currencies.get(currency), params);
            records = parseRecords(response, currencies.get(currency), client.providerCode());
        }
        String next;
        if (window.end().isBefore(query.to())) {
            next = currency + ":" + window.end().toEpochMilli();
        } else if (currency + 1 < currencies.size()) {
            next = (currency + 1) + ":" + query.from().toEpochMilli();
        } else {
            next = null;
        }
        return new BetPullPage(records, next, next != null);
    }

    /**
     * One FC request: the seconds {@code [first, last]} (FC's EndDate is inclusive) covering {@code [start, end)}.
     * {@code end} is at most 15 minutes after start and never crosses the 2 h history boundary (a minute, so that
     * consecutive pages agree on it). A window within one second is not requested; the next window starts in that second.
     */
    record Window(Instant end, boolean history, Instant first, Instant last) {

        boolean requested() {
            return !last.isBefore(first);
        }
    }

    static Window window(Instant start, Instant to, Instant now) {
        Instant boundary = now.minus(RECENT).truncatedTo(ChronoUnit.MINUTES);
        boolean history = start.isBefore(boundary);
        Instant end = start.plus(MAX_QUERY).isBefore(to) ? start.plus(MAX_QUERY) : to;
        if (history && end.isAfter(boundary)) {
            end = boundary;
        }
        return new Window(end, history, start.truncatedTo(ChronoUnit.SECONDS),
                end.truncatedTo(ChronoUnit.SECONDS).minusSeconds(1));
    }

    /** Settled records only: payout = bet + winlose; the request currency is the record's (FC sends none). */
    static List<ProviderBetRecordView> parseRecords(JsonNode response, String currency, String providerCode) {
        List<ProviderBetRecordView> records = new ArrayList<>();
        for (JsonNode r : response.path("Records")) {
            String recordId = text(r, "recordID");
            Long userId = PlayerIds.tryDecode(text(r, "account"));
            if (userId == null) {
                log.warn("FC record {} belongs to unknown player {}", recordId, text(r, "account"));
                continue;
            }
            BigDecimal bet = decimalOrZero(r, "bet");
            Instant betTime = recordTime(text(r, "bdate"));
            records.add(new ProviderBetRecordView(providerCode, recordId, recordId, userId, currency, text(r, "gameID"),
                    bet, bet.add(decimalOrZero(r, "winlose")), "SETTLED", betTime, null));
        }
        return records;
    }

    /** No round query in the FC seamless API: open rounds are escalated by the resolver. */
    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    /** The decrypted, Sign-checked JSON payload; any failure to decrypt or verify is an authentication failure. */
    static JsonNode decrypt(CallbackRequest request, ProviderClient client) {
        Map<String, String> form = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        form.putAll(Forms.body(request));
        if (!Objects.equals(client.config().operatorId(), form.get("AgentCode"))) {
            throw CallbackException.auth("unknown AgentCode");
        }
        String params = form.get("Params");
        String sign = form.get("Sign");
        if (params == null || params.isBlank() || sign == null || sign.isBlank()) {
            throw CallbackException.auth("missing Params or Sign");
        }
        String json = open(params, client.secret());
        if (!Hmacs.safeEquals(Ciphers.md5Hex(json), sign.strip().toLowerCase(Locale.ROOT))) {
            throw CallbackException.auth("Sign mismatch");
        }
        try {
            JsonNode node = JsonUtils.mapper().readTree(json);
            if (!node.isObject()) {
                throw CallbackException.badRequest("Params is not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw CallbackException.badRequest("Params is not JSON");
        }
    }

    /** Base64 (a '+' URL-decoded to ' ' by a sloppy form encoder is restored), AES/ECB/PKCS5, strict UTF-8. */
    static String open(String params, String key) {
        try {
            byte[] sealed = Base64.getDecoder().decode(params.strip().replace(' ', '+'));
            byte[] plain = Ciphers.aesDecrypt(sealed, Ciphers.utf8(key), null, Ciphers.Padding.PKCS5);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plain))
                    .toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw CallbackException.auth("Params does not decrypt");
        }
    }

    static String seal(String json, String key) {
        return Base64.getEncoder().encodeToString(Ciphers.aesEncrypt(Ciphers.utf8(json), Ciphers.utf8(key), null, Ciphers.Padding.PKCS5));
    }

    /** Ts may be seconds or milliseconds since the epoch. */
    private static void checkTs(JsonNode b, CallbackRequest request, ProviderClient client) {
        long ts = Fields.longValue(text(b, "Ts"), "Ts");
        Fields.checkTimestamp(ts > 100_000_000_000L ? Instant.ofEpochMilli(ts) : Instant.ofEpochSecond(ts), request, client);
    }

    private static String member(JsonNode b) {
        return Fields.required(text(b, "MemberAccount"), "MemberAccount");
    }

    /** Outbound call: JSON {@code {AgentCode, Currency, Params, Sign}}; Result 0 or an exception. */
    private static JsonNode call(ProviderClient client, String path, String currency, Map<String, Object> params) {
        String json = JsonUtils.toJson(params);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("AgentCode", client.config().operatorId());
        if (currency != null) {
            body.put("Currency", currency);
        }
        body.put("Params", seal(json, client.secret()));
        body.put("Sign", Ciphers.md5Hex(json));
        String response = client.rest().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonUtils.toJson(body))
                .retrieve()
                .body(String.class);
        JsonNode node = JsonUtils.mapper().readTree(response);
        if (node.path("Result").asInt(-1) != 0) {
            throw new IllegalStateException("FC " + path + " failed: Result " + node.path("Result").asString("?"));
        }
        return node;
    }

    /** FC LanguageID: 1 English, 2 simplified Chinese, 3 Vietnamese, 4 Thai, 5 Indonesian, 7 Japanese, 8 Korean. */
    static String language(String language) {
        if (language == null || language.length() < 2) {
            return "1";
        }
        return LANGUAGES.getOrDefault(language.substring(0, 2).toLowerCase(Locale.ROOT), "1");
    }

    private static String fcTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, FC_ZONE).format(FC_TIME);
    }

    private static Instant recordTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value.strip(), FC_TIME).toInstant(FC_ZONE);
    }

    /** A field matched case-insensitively (FC's own casing varies: GameID / gameID / GameId ...), null when absent. */
    private static JsonNode field(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Text of a scalar field (numbers as written), null when absent or JSON null. */
    private static String text(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isValueNode() ? value.asString() : value.toString();
    }

    private static BigDecimal decimalOrZero(JsonNode node, String name) {
        String value = text(node, name);
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.strip());
    }

    private static BigDecimal nonNegative(String value, String field) {
        BigDecimal amount = Fields.amount(value, field);
        if (amount.signum() < 0) {
            throw CallbackException.badRequest(field + " must not be negative");
        }
        return amount;
    }

    private static CallbackResponse error(int result, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Result", result);
        body.put("ErrorText", text);
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }
}
