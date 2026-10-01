package com.bingo789.game.adapter.jili;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * JILI seamless wallet ("JiLi_Seamless" 4.2.x): callbacks {@code POST /callback/JILI/<auth|bet|cancelBet|sessionBet|
 * cancelSessionBet>}, JSON in and out, always HTTP 200 with the result in {@code errorCode}.
 * <p>
 * Auth: JILI signs nothing; callbacks are admitted by the IP allow-list only. The launch token is our game token (the
 * old self-signed HMAC token is gone) and JILI presents it on every call. Debits ({@code bet}, {@code sessionBet} type 1)
 * and {@code auth} run on that token ({@link WalletCommand.Session}). Credits and reversals ({@code cancelBet},
 * {@code cancelSessionBet}, {@code sessionBet} type 2, a stakeless jackpot with {@code userId}) identify the player by
 * {@code userId} (= the {@code username} we answered in auth), because they must succeed after the token expired or
 * the player was suspended. JILI expects a token in every reply: we echo the same one, it stays valid (sliding expiry).
 * <p>
 * Ids: {@code round} (WagersId, may exceed int64) is kept as text. {@code bet} = one BetAndPayout with bet id = payout
 * id = round (the wallet keys on id + type), round closed. A jackpot without stake (statementType 33-36, betAmount 0)
 * is a JACKPOT_PAYOUT. {@code cancelBet} reverses bet and payout of the round (Batch: bet rollback first, so a cancel
 * that overtakes its bet leaves a tombstone and the late bet is refused; "round not found" (2) when no payout existed).
 * {@code sessionBet}: round = sessionId, bet / settle id = {@code sessionId_round}; type 1 debits {@code preserve} when
 * given, else {@code betAmount}; type 2 pays {@code preserve - betAmount + winloseAmount} (just the win without
 * preserve) and closes the round; a negative settlement is an Adjust plus a zero payout closing the round.
 * Duplicates answer 1 ("already accepted", JILI's duplicate code) with the current balance.
 * <p>
 * Deliberate deviations from the old code: durable idempotency instead of 1 h Redis keys; cancels and settlements no
 * longer fail with 4/5 once the token is older than 24 h; cancelBet has no 2 h lookup window and no betAmount equality
 * check (the wallet reverses the recorded amounts); a cancel before its bet is a tombstone (the old code answered 2 and
 * accepted the late bet); a cancel that makes the balance negative follows bingo.wallet.negative-balance-policy (ALLOW:
 * applied, the old code answered 6); an unknown or expired token is 4 (old: 5 for unknown); a locked wallet is 2
 * (terminal, like the old betting-limit block); a type 2 settle without a live bet is 2 "round not found" (old: paid
 * anyway); /bet no longer compensates a debit when its credit key is a duplicate (BetAndPayout is atomic); balances are
 * rounded DOWN; pull errors fail the run (the old job swallowed them and advanced its cursor); the VND/IDR 1:1000 pull
 * factor is not ported (PHP only).
 * <p>
 * Framework gaps: BetAndPayout has no payout type, so free-spin wins and jackpots with a stake are recorded as PAYOUT;
 * a Batch is not atomic, so under negative-balance-policy REJECT a refused payout reversal leaves the bet refunded
 * (answered 6); the wallet cannot refund part of a bet, so with {@code preserve} the ledger stake is the reserve and
 * the unused reserve comes back inside the payout (turnover overstated); card-game (sessionBet) rounds are sessionId in
 * the wallet but WagersId in the bet history, so they may not match by round in reconciliation (unverified).
 * <p>
 * Configuration ({@code bingo.providers.JILI}): {@code operator-id} = AgentId, {@code secret} = agent key (signs the
 * outbound {@code Key}), {@code base-url} = JILI API domain ({@code /singleWallet/...} and {@code /api1/...} are appended),
 * {@code currencies} = the account currency (history records carry none, the first one is used); optional setting
 * {@code trialUrl} (demo play: {@code <trialUrl>/<gameId>/<lang>}). No secrets map. Bet pull: JILI writes history 5-10 min
 * late, so {@code bingo.bet-record.pull.providers.JILI.settle-delay: 10m}; {@code page-size: 5000} (PageLimit, max
 * 20000 for bets / 10000 for free spins).
 */
@Slf4j
@Component
public class JiliAdapter implements ProviderAdapter {

    static final String NAME = "JILI";

    /** JILI's API clock: signing date and history query times are UTC-4. */
    static final ZoneOffset JILI_ZONE = ZoneOffset.ofHours(-4);

    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    /** Days 1-9 are signed without the leading zero (vendor rule). */
    private static final DateTimeFormatter KEY_DATE_SHORT = DateTimeFormatter.ofPattern("yyMMd");
    /** Appendix C statement types 33-36: Jackpot Legend Mini / Minor / Major / Grand. */
    private static final Set<Integer> JACKPOT_TYPES = Set.of(33, 34, 35, 36);
    /** Free-game wagers of GetFreeSpinRecordByTime; 27 / 28 (spin bonus) are not free spins. */
    private static final int FREE_GAME_TYPE = 19;
    private static final int MAX_BET_PAGE = 20_000;
    private static final int MAX_FREE_SPIN_PAGE = 10_000;
    private static final String BETS = "bet";
    private static final String FREE_SPINS = "free";

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    /** JILI signs nothing: callbacks are admitted by the IP allow-list, players by token or userId. */
    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        JsonNode body = json(request);
        return switch (request.action()) {
            case "auth" -> new WalletCommand.Authenticate(required(body, "token"), null);
            case "bet" -> bet(body);
            case "cancelBet" -> cancelBet(body);
            case "sessionBet" -> sessionBet(body);
            case "cancelSessionBet" -> cancelSessionBet(body);
            default -> throw CallbackException.unknownAction(request.action());
        };
    }

    /** Slot round: stake and gross win in one call. */
    private static WalletCommand bet(JsonNode body) {
        String token = required(body, "token");
        String currency = required(body, "currency");
        String round = required(body, "round");
        String game = text(body, "game");
        BigDecimal stake = nonNegative(body, "betAmount");
        BigDecimal win = nonNegative(body, "winloseAmount");
        // 4.2.3: isFreeRound marks an offline draw, which never carries a stake
        if (body.path("isFreeRound").asBoolean(false) && stake.signum() > 0) {
            throw CallbackException.badRequest("isFreeRound with a stake");
        }
        if (stake.signum() == 0 && JACKPOT_TYPES.contains(body.path("statementType").asInt(0))) {
            String userId = text(body, "userId");
            WalletCommand.Payout jackpot = new WalletCommand.Payout(userId, currency, round, round, game, win,
                    TxnType.JACKPOT_PAYOUT, null, true);
            return userId != null ? jackpot : new WalletCommand.Session(token, jackpot);
        }
        return new WalletCommand.Session(token, new WalletCommand.BetAndPayout(null, currency, round, round, round, game,
                stake, win, true));
    }

    /** Reverses the bet and the win of a /bet round (the amounts in the request are informational). */
    private static WalletCommand cancelBet(JsonNode body) {
        String player = required(body, "userId");
        String currency = required(body, "currency");
        String round = required(body, "round");
        String game = text(body, "game");
        String rollbackId = "cancel:" + round;
        return new WalletCommand.Batch(player, currency, List.of(
                new WalletCommand.Rollback(player, currency, rollbackId, round, TxnType.PAYOUT, round, game),
                new WalletCommand.Rollback(player, currency, rollbackId, round, TxnType.BET, round, game)));
    }

    private static WalletCommand sessionBet(JsonNode body) {
        String currency = required(body, "currency");
        String session = required(body, "sessionId");
        String txnId = session + "_" + required(body, "round");
        String game = text(body, "game");
        BigDecimal stake = nonNegative(body, "betAmount");
        BigDecimal win = nonNegative(body, "winloseAmount");
        BigDecimal preserve = body.path("preserve").asDecimal(BigDecimal.ZERO);
        if (preserve.signum() < 0) {
            throw CallbackException.badRequest("preserve must not be negative");
        }
        return switch (body.path("type").asInt(0)) {
            case 1 -> new WalletCommand.Session(required(body, "token"), new WalletCommand.Bet(null, currency, txnId,
                    session, game, preserve.signum() > 0 ? preserve : stake, false));
            case 2 -> {
                String player = required(body, "userId");
                // with preserve the reserve was debited: its unused part comes back with the win
                BigDecimal settle = preserve.signum() > 0 ? preserve.subtract(stake).add(win) : win;
                if (settle.signum() >= 0) {
                    yield new WalletCommand.Payout(player, currency, txnId, session, game, settle, TxnType.PAYOUT, null, true);
                }
                yield new WalletCommand.Batch(player, currency, List.of(
                        new WalletCommand.Adjust(player, currency, txnId, null, session, settle, "JILI session settlement"),
                        new WalletCommand.Payout(player, currency, txnId, session, game, BigDecimal.ZERO, TxnType.PAYOUT, null, true)));
            }
            default -> throw CallbackException.badRequest("type must be 1 or 2");
        };
    }

    /** Cancels one sessionBet type 1 bet; the wallet refunds the amount it debited (preserve or betAmount). */
    private static WalletCommand cancelSessionBet(JsonNode body) {
        if (body.path("type").asInt(1) != 1) {
            throw CallbackException.badRequest("type must be 1");
        }
        String player = required(body, "userId");
        String session = required(body, "sessionId");
        String txnId = session + "_" + required(body, "round");
        return new WalletCommand.Rollback(player, required(body, "currency"), "cancel:" + txnId, txnId, TxnType.BET,
                session, text(body, "game"));
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        boolean cancel = request.action().startsWith("cancel");
        Reply reply = switch (outcome.code()) {
            case SUCCESS -> outcome.replay() ? new Reply(1, "already accepted") : new Reply(0, "success");
            // only a reversal refused by negative-balance-policy REJECT reaches a cancel
            case INSUFFICIENT_FUNDS -> cancel ? new Reply(6, "already accepted and cannot be canceled")
                    : new Reply(2, "not enough balance");
            case INVALID_TOKEN -> new Reply(4, "token expired");
            case PLAYER_NOT_FOUND -> new Reply(5, "user not found");
            // terminal on purpose: JILI must not retry a bet of a locked player
            case PLAYER_LOCKED -> new Reply(2, "player is locked");
            case BET_NOT_FOUND, TXN_NOT_FOUND -> new Reply(2, "round not found");
            case TXN_CANCELLED, BET_SETTLED -> new Reply(2, "round cancelled");
            case INVALID_REQUEST -> new Reply(3, "invalid parameter");
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", reply.code());
        body.put("message", reply.message());
        if (outcome.code() != CommandOutcome.Code.INVALID_TOKEN) {
            String token = text(json(request), "token");
            if (token != null) {
                body.put("token", token);
            }
        }
        if (reply.code() <= 1) {
            body.put("username", outcome.playerId());
            body.put("currency", outcome.currency());
            body.put("balance", number(Fields.balance(outcome.balance(), client.config().balanceScale())));
            if (!request.action().equals("auth") && outcome.platformTxnId() != null) {
                body.put("txId", outcome.platformTxnId());
            }
        }
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        Reply reply = switch (error) {
            case BAD_REQUEST, UNKNOWN_ACTION -> new Reply(3, "invalid parameter");
            // JILI retries (or cancels) on 5
            case AUTH_FAILED, RATE_LIMITED, SYSTEM_RETRYABLE -> new Reply(5, "other error");
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", reply.code());
        body.put("message", reply.message());
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    private record Reply(int code, String message) {
    }

    // ================================================================ outbound

    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        String lang = language(c.language());
        if (c.demo()) {
            return new LaunchView(client.setting("trialUrl") + "/" + c.gameCode() + "/" + lang, null);
        }
        String agentId = client.config().operatorId();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Token", c.gameToken());
        params.put("GameId", c.gameCode());
        params.put("Lang", lang);
        params.put("AgentId", agentId);
        params.put("Key", key("Token=" + c.gameToken() + "&GameId=" + c.gameCode() + "&Lang=" + lang,
                agentId, client.secret(), today()));
        // HomeUrl is not signed
        if (c.lobbyUrl() != null) {
            params.put("HomeUrl", c.lobbyUrl());
        }
        JsonNode response = post(client, "/singleWallet/LoginWithoutRedirect", params);
        requireOk(response, "LoginWithoutRedirect");
        String url = response.path("Data").asString(null);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("JILI LoginWithoutRedirect returned no game url");
        }
        return new LaunchView(url, null);
    }

    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        String agentId = client.config().operatorId();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("AgentId", agentId);
        params.put("Key", key("", agentId, client.secret(), today()));
        JsonNode response = post(client, "/api1/GetGameList", params);
        requireOk(response, "GetGameList");
        List<ProviderGameView> games = new ArrayList<>();
        for (JsonNode g : response.path("Data")) {
            String gameId = g.path("GameId").asString(null);
            if (gameId == null || gameId.isBlank()) {
                continue;
            }
            JsonNode name = g.path("name");
            String display = name.isObject() ? name.path("en-US").asString(null) : name.asString(null);
            games.add(new ProviderGameView(client.providerCode(), gameId, display == null ? gameId : display,
                    category(g.path("GameCategoryId").asInt(0)), null, null, true, true));
        }
        return games;
    }

    /**
     * Two page-numbered streams per window: GetBetRecordByTime, then GetFreeSpinRecordByTime (free-game wagers are not
     * in the first). Cursor {@code <stream>:<page>}; a stream ends with a page shorter than PageLimit. Records are
     * re-read by the overlap and upserted by WagersId, which also picks up late settlements.
     */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        String cursor = query.cursor() == null ? BETS + ":1" : query.cursor();
        int colon = cursor.indexOf(':');
        boolean freeSpins = cursor.startsWith(FREE_SPINS);
        int page = Integer.parseInt(cursor.substring(colon + 1));
        int max = freeSpins ? MAX_FREE_SPIN_PAGE : MAX_BET_PAGE;
        int limit = query.pageSize() > 0 ? Math.min(query.pageSize(), max) : 5_000;

        String agentId = client.config().operatorId();
        String start = QUERY_TIME.format(query.from().atOffset(JILI_ZONE));
        String end = QUERY_TIME.format(query.to().atOffset(JILI_ZONE));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("AgentId", agentId);
        params.put("StartTime", start);
        params.put("EndTime", end);
        params.put("Page", String.valueOf(page));
        params.put("PageLimit", String.valueOf(limit));
        if (freeSpins) {
            params.put("FilterAgent", "1");
        }
        params.put("Key", key("StartTime=" + start + "&EndTime=" + end + "&Page=" + page + "&PageLimit=" + limit,
                agentId, client.secret(), today()));
        String response = client.rest().post()
                .uri(freeSpins ? "/api1/GetFreeSpinRecordByTime" : "/api1/GetBetRecordByTime")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Forms.encode(params))
                .retrieve()
                .body(String.class);
        HistoryPage history = parseHistory(response, freeSpins, accountCurrency(client), client.providerCode());

        String next;
        if (history.rows() >= limit) {
            next = (freeSpins ? FREE_SPINS : BETS) + ":" + (page + 1);
        } else {
            next = freeSpins ? null : FREE_SPINS + ":1";
        }
        return new BetPullPage(history.records(), next, next != null);
    }

    /** @param rows rows the page had, including skipped ones (paging depends on it) */
    record HistoryPage(List<ProviderBetRecordView> records, int rows) {
    }

    /**
     * {@code {"ErrorCode":0,"Message":"","Data":{"Result":[...]}}}; the free-spin endpoint answers ErrorCode 101 when
     * the window is empty and may put the rows directly in {@code Data}. Bet records without {@code Status} are unsettled.
     */
    static HistoryPage parseHistory(String json, boolean freeSpins, String currency, String providerCode) {
        JsonNode response = JsonUtils.mapper().readTree(json);
        int error = response.path("ErrorCode").asInt(0);
        if (freeSpins && error == 101) {
            return new HistoryPage(List.of(), 0);
        }
        if (error != 0) {
            throw new IllegalStateException("JILI history failed: ErrorCode " + error + " " + response.path("Message").asString(""));
        }
        JsonNode rows = rowsOf(response.path("Data"));
        List<ProviderBetRecordView> records = new ArrayList<>();
        for (JsonNode r : rows) {
            if (freeSpins && r.path("Type").asInt(0) != FREE_GAME_TYPE) {
                continue;
            }
            String wagersId = r.path("WagersId").asString(null);
            Long userId = PlayerIds.tryDecode(r.path("Account").asString(null));
            if (userId == null) {
                log.warn("JILI wager {} belongs to unknown player {}", wagersId, r.path("Account").asString(null));
                continue;
            }
            boolean settled = freeSpins || !r.path("Status").asString("").isBlank();
            String recordCurrency = freeSpins ? r.path("Currency").asString(currency) : currency;
            BigDecimal stake = freeSpins ? BigDecimal.ZERO : r.path("BetAmount").asDecimal(BigDecimal.ZERO).abs();
            records.add(new ProviderBetRecordView(providerCode, wagersId, wagersId, userId,
                    recordCurrency == null || recordCurrency.isBlank() ? currency : recordCurrency,
                    r.path("GameId").asString(null), stake, r.path("PayoffAmount").asDecimal(BigDecimal.ZERO),
                    settled ? "SETTLED" : "OPEN", time(r.path("WagersTime").asString(null)),
                    settled ? time(r.path("SettlementTime").asString(null)) : null));
        }
        return new HistoryPage(records, rows.size());
    }

    /** No round query in the JILI API: /bet rounds close at once, session rounds by their settle or cancel. */
    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    /**
     * {@code Key = 6 random digits + md5(signed + "&AgentId=" + agentId + keyG) + 6 random digits}, with
     * {@code keyG = md5(date + agentId + agentKey)} and the UTC-4 date as yyMMdd (yyMMd on days 1-9). Only the
     * mandatory parameters of a call are signed, in the documented order.
     */
    static String key(String signed, String agentId, String agentKey, LocalDate day) {
        String sign = Ciphers.md5Hex((signed.isEmpty() ? "" : signed + "&") + "AgentId=" + agentId + keyG(agentId, agentKey, day));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return random.nextInt(100_000, 1_000_000) + sign + random.nextInt(100_000, 1_000_000);
    }

    static String keyG(String agentId, String agentKey, LocalDate day) {
        String date = (day.getDayOfMonth() >= 10 ? KEY_DATE : KEY_DATE_SHORT).format(day);
        return Ciphers.md5Hex(date + agentId + agentKey);
    }

    private static LocalDate today() {
        return LocalDate.now(JILI_ZONE);
    }

    private static JsonNode post(ProviderClient client, String path, Map<String, String> params) {
        String response = client.rest().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Forms.encode(params))
                .retrieve()
                .body(String.class);
        return JsonUtils.mapper().readTree(response);
    }

    private static void requireOk(JsonNode response, String call) {
        int error = response.path("ErrorCode").asInt(-1);
        if (error != 0) {
            throw new IllegalStateException("JILI " + call + " failed: ErrorCode " + error + " " + response.path("Message").asString(""));
        }
    }

    /** {@code Data.Result}, or {@code Data} itself / its first array member. */
    private static JsonNode rowsOf(JsonNode data) {
        if (data.isArray()) {
            return data;
        }
        JsonNode result = data.path("Result");
        if (result.isArray()) {
            return result;
        }
        for (Map.Entry<String, JsonNode> field : data.properties()) {
            if (field.getValue().isArray()) {
                return field.getValue();
            }
        }
        return result;
    }

    /**
     * History times carry an offset ({@code 2022-12-19T02:20:31-04:00}); without one they are UTC-4. Unreadable
     * times are null: bet-record then skips that record with a warning instead of failing the whole window.
     */
    private static Instant time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value).toInstant(JILI_ZONE);
            } catch (DateTimeParseException unreadable) {
                log.warn("JILI history time {} is unreadable", value);
                return null;
            }
        }
    }

    private static String accountCurrency(ProviderClient client) {
        List<String> currencies = client.config().currencies();
        if (currencies.isEmpty()) {
            throw new IllegalStateException("provider " + client.providerCode() + ": currencies must be configured");
        }
        return currencies.getFirst();
    }

    private static String language(String language) {
        if (language == null || language.isBlank()) {
            return "en-US";
        }
        return switch (language.toLowerCase(Locale.ROOT).split("[-_]")[0]) {
            case "zh" -> "zh-CN";
            case "ja" -> "ja-JP";
            case "th" -> "th-TH";
            case "vi" -> "vi-VN";
            case "id" -> "id-ID";
            case "ko" -> "ko-KR";
            default -> "en-US";
        };
    }

    private static String category(int gameCategoryId) {
        return switch (gameCategoryId) {
            case 1 -> "Slot";
            case 2 -> "Poker";
            case 3 -> "Casino";
            case 5 -> "Fishing";
            case 8 -> "Bingo";
            default -> null;
        };
    }

    private static JsonNode json(CallbackRequest request) {
        try {
            JsonNode node = JsonUtils.mapper().readTree(request.body());
            if (node == null || !node.isObject()) {
                throw CallbackException.badRequest("body is not a json object");
            }
            return node;
        } catch (JacksonException e) {
            throw CallbackException.badRequest("malformed json");
        }
    }

    /** Text of a scalar field (numbers included, e.g. a round beyond int64); null when absent or blank. */
    private static String text(JsonNode body, String field) {
        String value = body.path(field).asString(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String required(JsonNode body, String field) {
        return Fields.required(text(body, field), field);
    }

    private static BigDecimal nonNegative(JsonNode body, String field) {
        JsonNode node = body.path(field);
        BigDecimal value = node.isMissingNode() || node.isNull() ? null : node.asDecimal(null);
        if (value == null) {
            throw CallbackException.badRequest(field + " is required and must be a number");
        }
        if (value.signum() < 0) {
            throw CallbackException.badRequest(field + " must not be negative");
        }
        return value;
    }

    private static BigDecimal number(String balance) {
        return balance == null ? BigDecimal.ZERO : new BigDecimal(balance);
    }
}
