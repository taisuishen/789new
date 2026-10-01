package com.bingo789.game.adapter.ygr;

import com.bingo789.common.core.crypto.Hmacs;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.game.adapter.ProviderAdapter;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.OpenBet;
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
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * YGR (slots and fishing) seamless wallet, "YGR SEAMLESS WALLET API (Multi-Wallet)": callbacks under
 * {@code /callback/YGR/}{@code token/...}, {@code transaction/...}, JSON bodies (GET query for
 * getConnectTokenAmount), replies {@code {"data":{..},"status":{"code":"0","message":..,"dateTime":..,"traceCode":..}}},
 * always HTTP 200, {@code data} omitted on errors. Amounts are decimal currency units.
 * <p>
 * Auth: a static {@code Authorization} header, compared in constant time with the configured secret (one merchant
 * account per provider code; the old code accepted four hard-coded values). The player is identified by
 * {@code connectToken} on every call = our game token (a {@link WalletCommand.Session}), followed by
 * {@code .<gameCode>}: YGR reads the game to open from the authorizationConnectToken reply, and our opaque token does
 * not carry it. The suffix is stripped before the token is verified.
 * <p>
 * Idempotency: addGameResult (slots) is one atomic BetAndPayout, bet keyed {@code transID}, payout keyed
 * {@code roundID} (YGR's "round id duplicated" semantics), round {@code roundID}. Fishing: rollOut is a BET
 * ({@code transID}) in round {@code roundID} of game {@code <gameCode>}; with {@code takeAll} the whole balance (rounded
 * down to {@code balance-scale}) is taken and answered in {@code data.amount}, the first amount on a retry. rollIn is the
 * PAYOUT ({@code transID}) closing that round, refund reverses exactly the rollOut {@code transID} (before its rollOut:
 * tombstone, the late rollOut is then rejected). Duplicates answer YGR's duplicate codes: 208 for addGameResult, 203
 * for rollOut / rollIn / refund.
 * <p>
 * Fishing compensation ("補單"): rollOuts are tracked with their connectToken ({@link #tracksOpenBets}, see
 * OpenBetLedger). {@code betSlip/roundCheck} answers the rollOuts received in [fromDate, toDate) that are neither rolled
 * in nor refunded, including those whose wallet call did not answer (their amount is 0 when a takeAll's amount is not
 * known); YGR then resends their rollIn or refunds them, with that connectToken even after it expired.
 * <p>
 * Deliberate deviations from the old integration: addGameResult can no longer pay the win without the bet (the old
 * compensation left the bet marker, so YGR's retry credited the win on top of the refunded bet); insufficient funds
 * answer 204 (the old addGameResult answered 999); rollIn needs a live rollOut in the round (requireBetForPayout);
 * a refund of an unknown rollOut succeeds (tombstone) instead of 404; no 24-hour lookup window; balances are rounded
 * DOWN (old: HALF_UP); status times are UTC+8.
 * <p>
 * Not supported (answered 404): {@code token/createGuestConnectToken} (tokens are issued only by user-service at
 * launch). {@code token/delConnectToken} answers success for a valid token but does not revoke it (no revocation API;
 * the token expires by its own TTL). Gaps: a refund after a rollIn is refused by the wallet (BET_SETTLED) and answered
 * 203; fishing records of the bet history are per wager and do not match the rollOut / rollIn round totals.
 * <p>
 * Configuration ({@code bingo.providers.YGR}): {@code secret} = the callback Authorization value, {@code operator-id} =
 * agentId (e.g. {@code 789_BETBINGO_PHP}), {@code secrets.agentKey} = agent secret key (bet-history Key),
 * {@code base-url} = user API URL (launch), settings {@code manageApiUrl} (bet history API URL), optional
 * {@code supplier} (default: the first two '_' segments of the agentId). Bet pull: page-number paging (10000 rows);
 * records are indexed by wager time and settle later, so use
 * {@code bingo.bet-record.pull.providers.YGR.overlap: 10m} and {@code settle-delay: 5m}.
 */
@Slf4j
@Component
public class YgrAdapter implements ProviderAdapter {

    static final String NAME = "YGR";

    static final int OK = 0;
    static final int BAD_PARAMETER = 201;
    static final int TRANSACTION_ID_DUPLICATED = 203;
    static final int INSUFFICIENT_BALANCE = 204;
    static final int ACCOUNT_NOT_EXIST = 205;
    static final int ROUND_ID_DUPLICATED = 208;
    static final int UNAUTHORIZED = 401;
    static final int NOT_FOUND = 404;
    static final int SOMETHING_WRONG = 999;

    static final int PAGE_LIMIT = 10000;

    private static final DateTimeFormatter STATUS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter RECORD_WINDOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /** Day part of the bet-history key: two-digit year and month, day NOT zero-padded (2026-10-01 = "26101"). */
    private static final DateTimeFormatter KEY_DAY = DateTimeFormatter.ofPattern("yyMMd");
    private static final String ADD_GAME_RESULT = "transaction/addGameResult";
    private static final String ROLL_OUT = "transaction/rollOut";
    private static final String ROUND_CHECK = "betSlip/roundCheck";
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
        String authorization = request.header("Authorization");
        if (authorization == null || !Hmacs.safeEquals(client.secret(), authorization.strip())) {
            throw CallbackException.auth("missing or wrong Authorization");
        }
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        return switch (request.action()) {
            case "token/authorizationConnectToken" -> new WalletCommand.Authenticate(sessionToken(json(request)), null);
            case "token/getConnectTokenAmount" -> new WalletCommand.Session(
                    sessionToken(Forms.query(request).get("connectToken")), new WalletCommand.GetBalance(null, null));
            case "token/delConnectToken" -> new WalletCommand.Session(sessionToken(json(request)), new WalletCommand.Ack(null, null));
            case ADD_GAME_RESULT -> addGameResult(json(request));
            case ROLL_OUT -> {
                JsonNode b = json(request);
                WalletCommand stake = b.path("takeAll").asBoolean(false)
                        ? new WalletCommand.TakeAll(null, null, text(b, "transID"), text(b, "roundID"), gameId(b))
                        : new WalletCommand.Bet(null, null, text(b, "transID"), text(b, "roundID"), gameId(b),
                        decimal(b, "amount"), false);
                yield new WalletCommand.Session(sessionToken(b), stake);
            }
            case "transaction/rollIn" -> {
                JsonNode b = json(request);
                yield new WalletCommand.Session(sessionToken(b), new WalletCommand.Payout(null, null, text(b, "transID"),
                        text(b, "roundID"), null, decimal(b, "amount"), TxnType.PAYOUT, null, true));
            }
            // transID is the rollOut's transID; the refund carries no round
            case "transaction/refund" -> {
                JsonNode b = json(request);
                String rollOut = text(b, "transID");
                yield new WalletCommand.Session(sessionToken(b), new WalletCommand.Rollback(null, null, "refund:" + rollOut,
                        rollOut, TxnType.BET, null, null));
            }
            case ROUND_CHECK -> roundCheck(json(request));
            // token/createGuestConnectToken is not supported (see class comment)
            default -> throw CallbackException.unknownAction(request.action());
        };
    }

    /** [fromDate, toDate), RFC 3339. */
    private static WalletCommand roundCheck(JsonNode b) {
        Instant from = requiredTime(b, "fromDate");
        Instant to = requiredTime(b, "toDate");
        if (!from.isBefore(to)) {
            throw CallbackException.badRequest("fromDate must be before toDate");
        }
        return new WalletCommand.OpenBets(from, to);
    }

    @Override
    public boolean tracksOpenBets() {
        return true;
    }

    /** Slots: stake and win of one spin in one call; winLoseAmount must be payoutAmount - betAmount. */
    private static WalletCommand addGameResult(JsonNode b) {
        String round = text(b, "roundID");
        BigDecimal bet = decimal(b, "betAmount");
        BigDecimal payout = decimal(b, "payoutAmount");
        if (payout.subtract(bet).compareTo(decimal(b, "winLoseAmount")) != 0) {
            throw CallbackException.badRequest("winLoseAmount is not payoutAmount - betAmount");
        }
        return new WalletCommand.Session(sessionToken(b), new WalletCommand.BetAndPayout(null, null, text(b, "transID"), round,
                round, null, bet, payout, true));
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        String action = request.action();
        int code = switch (outcome.code()) {
            case SUCCESS -> !outcome.replay() || !action.startsWith("transaction/") ? OK
                    : action.equals(ADD_GAME_RESULT) ? ROUND_ID_DUPLICATED : TRANSACTION_ID_DUPLICATED;
            case INSUFFICIENT_FUNDS, PLAYER_LOCKED -> INSUFFICIENT_BALANCE;
            case INVALID_TOKEN -> UNAUTHORIZED;
            case PLAYER_NOT_FOUND -> ACCOUNT_NOT_EXIST;
            case BET_NOT_FOUND, TXN_NOT_FOUND -> NOT_FOUND;
            // a rollOut after its refund
            case TXN_CANCELLED, BET_SETTLED -> TRANSACTION_ID_DUPLICATED;
            case INVALID_REQUEST -> BAD_PARAMETER;
        };
        if (code != OK) {
            return reply(code, null);
        }
        if (action.equals(ROUND_CHECK)) {
            return reply(OK, openRounds(outcome, client));
        }
        BigDecimal balance = number(Fields.balance(outcome.balance(), client.config().balanceScale()));
        Map<String, Object> data = new LinkedHashMap<>();
        switch (action) {
            case "token/authorizationConnectToken" -> {
                data.put("ownerId", outcome.playerId());
                data.put("parentId", outcome.playerId());
                data.put("gameId", gameId(json(request)));
                data.put("userId", outcome.playerId());
                data.put("nickname", outcome.playerId());
                data.put("currency", outcome.currency());
                data.put("amount", balance);
            }
            case "token/getConnectTokenAmount" -> {
                data.put("currency", outcome.currency());
                data.put("amount", balance);
            }
            case "token/delConnectToken" -> {
                // empty data object
            }
            default -> {
                data.put("currency", outcome.currency());
                data.put("balance", balance);
                if (action.equals(ROLL_OUT) && command instanceof WalletCommand.Session s) {
                    // the amount the wallet took: a takeAll's is known only now, a retry's is the first one
                    BigDecimal taken = outcome.amount() != null ? outcome.amount()
                            : s.command() instanceof WalletCommand.Bet bet ? bet.amount() : null;
                    data.put("amount", number(Fields.balance(taken, client.config().balanceScale())));
                }
            }
        }
        return reply(OK, data);
    }

    /** roundCheck data[]: the connectToken as YGR got it at launch (token.gameCode), so it can settle the round with it. */
    private static List<Map<String, Object>> openRounds(CommandOutcome outcome, ProviderClient client) {
        List<Map<String, Object>> rounds = new ArrayList<>(outcome.openBets().size());
        for (OpenBet bet : outcome.openBets()) {
            Map<String, Object> round = new LinkedHashMap<>();
            round.put("transID", bet.txnId());
            round.put("roundID", bet.roundId());
            round.put("amount", number(Fields.balance(bet.amount() != null ? bet.amount() : BigDecimal.ZERO,
                    client.config().balanceScale())));
            round.put("connectToken", bet.gameCode() == null || bet.gameCode().isBlank() ? bet.token() : bet.token() + "." + bet.gameCode());
            round.put("rollTime", STATUS_TIME.format(bet.placedAt().atOffset(BingoTime.ZONE)));
            rounds.add(round);
        }
        return rounds;
    }

    /** 999 "Something wrong" is YGR's retryable error; never 0. */
    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        return reply(switch (error) {
            case AUTH_FAILED -> UNAUTHORIZED;
            case BAD_REQUEST -> BAD_PARAMETER;
            case UNKNOWN_ACTION -> NOT_FOUND;
            case RATE_LIMITED, SYSTEM_RETRYABLE -> SOMETHING_WRONG;
        }, null);
    }

    // ================================================================ outbound

    /** GET {base-url}/launch with connectToken = game token + "." + game code. */
    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        String connectToken = c.gameCode() == null || c.gameCode().isBlank() ? c.gameToken() : c.gameToken() + "." + c.gameCode();
        String response = client.rest().get()
                .uri(URI.create(client.config().baseUrl() + "/launch?token=" + Forms.encode(connectToken)
                        + "&language=" + Forms.encode(language(c.language()))))
                .header("Agent_Currency", client.config().operatorId())
                .header("Supplier", supplier(client))
                .retrieve()
                .body(String.class);
        return new LaunchView(launchUrl(response), null);
    }

    /** {@code {"ErrorCode":0,"Data":{"url":..}}} (or {@code Url}), or the bare URL. */
    static String launchUrl(String response) {
        String body = response == null ? "" : response.strip();
        if (body.startsWith("http")) {
            return body;
        }
        JsonNode root = JsonUtils.mapper().readTree(body);
        if (root.path("ErrorCode").asInt(0) != 0) {
            throw new IllegalStateException("YGR launch failed: " + root.path("ErrorCode").asInt() + " " + root.path("Message").asString(""));
        }
        String url = root.path("Data").path("url").asString(root.path("Data").path("Url").asString(""));
        if (url.isBlank()) {
            throw new IllegalStateException("YGR launch returned no url");
        }
        return url;
    }

    /** No game catalogue API is used for YGR; the lobby catalogue is maintained manually. */
    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        return List.of();
    }

    /** GetBetRecordByDateTime of [from, to) (UTC+8 times); the cursor is the next page number. */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        int page = query.cursor() == null ? 1 : Integer.parseInt(query.cursor());
        String agentId = client.config().operatorId();
        String start = RECORD_WINDOW.format(query.from().atOffset(BingoTime.ZONE));
        String end = RECORD_WINDOW.format(query.to().atOffset(BingoTime.ZONE));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("StartTime", start);
        body.put("EndTime", end);
        body.put("Page", page);
        body.put("PageLimit", PAGE_LIMIT);
        body.put("AgentId", agentId);
        body.put("Key", random6() + recordSign(start, end, page, agentId, client.secret("agentKey"), LocalDate.now(BingoTime.ZONE))
                + random6());
        String response = client.rest().post()
                .uri(URI.create(client.setting("manageApiUrl") + "/GetBetRecordByDateTime"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Supplier", supplier(client))
                .body(JsonUtils.toJson(body))
                .retrieve()
                .body(String.class);
        JsonNode root = JsonUtils.mapper().readTree(response);
        if (root.path("ErrorCode").asInt(0) != 0) {
            throw new IllegalStateException("YGR GetBetRecordByDateTime failed: " + root.path("ErrorCode").asInt() + " "
                    + root.path("Message").asString(""));
        }
        JsonNode pagination = root.path("Data").path("Pagination");
        int current = pagination.path("CurrentPage").asInt(page);
        boolean more = current < pagination.path("TotalPages").asInt(0);
        return new BetPullPage(parseRecords(root, client.providerCode()), more ? String.valueOf(current + 1) : null, more);
    }

    /** md5(query + md5(yyMMd + agentId + agentKey)), the day being today in UTC+8 (YGR's server zone). */
    static String recordSign(String start, String end, int page, String agentId, String agentKey, LocalDate today) {
        String keyG = Ciphers.md5Hex(KEY_DAY.format(today) + agentId + agentKey);
        return Ciphers.md5Hex("StartTime=" + start + "&EndTime=" + end + "&Page=" + page + "&PageLimit=" + PAGE_LIMIT
                + "&AgentId=" + agentId + keyG);
    }

    /**
     * {@code Data.result[]}: one record per wager ({@code WagersId} = the slot round id); {@code PayoffAmount} is the net
     * win/loss, so the payout is {@code BetAmount + PayoffAmount}; a null {@code Status} means not settled yet.
     */
    static List<ProviderBetRecordView> parseRecords(JsonNode root, String providerCode) {
        List<ProviderBetRecordView> records = new ArrayList<>();
        for (JsonNode r : root.path("Data").path("result")) {
            String account = r.path("Account").asString("");
            Long userId = PlayerIds.tryDecode(account);
            if (userId == null) {
                log.warn("YGR wager {} belongs to unknown player '{}'", r.path("WagersId").asString(""), account);
                continue;
            }
            String wagersId = r.path("WagersId").asString();
            boolean settled = !r.path("Status").isMissingNode() && !r.path("Status").isNull();
            BigDecimal bet = r.path("BetAmount").asDecimal();
            BigDecimal payout = settled ? bet.add(r.path("PayoffAmount").asDecimal()) : BigDecimal.ZERO;
            Instant betTime = time(r.path("WagersTime").asString(null));
            Instant settleTime = settled ? time(r.path("SettlementTime").asString(r.path("WagersTime").asString(null))) : null;
            records.add(new ProviderBetRecordView(providerCode, wagersId, wagersId, userId, currency(r.path("Currency").asString(null)),
                    r.path("GameId").asString(null), bet, payout, settled ? "SETTLED" : "OPEN", betTime, settleTime));
        }
        return records;
    }

    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    private static JsonNode json(CallbackRequest request) {
        if (request.body().length == 0) {
            throw CallbackException.badRequest("empty body");
        }
        try {
            return JsonUtils.mapper().readTree(request.body());
        } catch (JacksonException e) {
            throw CallbackException.badRequest("malformed json");
        }
    }

    private static String sessionToken(JsonNode body) {
        return sessionToken(body.path("connectToken").asString(null));
    }

    /** Our game token, without the ".gameCode" suffix added at launch. */
    static String sessionToken(String connectToken) {
        String value = Fields.required(connectToken, "connectToken").strip();
        int dot = value.indexOf('.');
        return dot < 0 ? value : value.substring(0, dot);
    }

    private static String gameId(JsonNode body) {
        String connectToken = body.path("connectToken").asString("");
        int dot = connectToken.indexOf('.');
        return dot < 0 ? null : connectToken.substring(dot + 1);
    }

    private static String text(JsonNode body, String field) {
        return Fields.required(body.path(field).asString(null), field);
    }

    private static BigDecimal decimal(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw CallbackException.badRequest(field + " is required");
        }
        try {
            return value.asDecimal();
        } catch (JacksonException e) {
            throw CallbackException.badRequest(field + " is not a number");
        }
    }

    private static String supplier(ProviderClient client) {
        String agentId = client.config().operatorId();
        String[] parts = agentId == null ? new String[0] : agentId.split("_");
        return client.setting("supplier", parts.length >= 2 ? parts[0] + "_" + parts[1] : agentId);
    }

    private static String random6() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    private static Instant requiredTime(JsonNode body, String field) {
        try {
            return time(text(body, field));
        } catch (DateTimeParseException e) {
            throw CallbackException.badRequest(field + " is not an RFC 3339 time");
        }
    }

    /** YGR times are UTC+8 wall clock ({@code yyyy-MM-dd'T'HH:mm:ss[.SSS]}), or RFC 3339 with an offset. */
    private static Instant time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(value.strip().replace(' ', 'T')).toInstant(BingoTime.ZONE);
        }
    }

    private static String currency(String ygrCurrency) {
        return "RMB".equals(ygrCurrency) ? "CNY" : ygrCurrency;
    }

    private static BigDecimal number(String value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /** @param data the {@code data} object (an array for roundCheck), omitted when null */
    private static CallbackResponse reply(int code, Object data) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("code", String.valueOf(code));
        status.put("message", message(code));
        status.put("dateTime", STATUS_TIME.format(OffsetDateTime.now(BingoTime.ZONE)));
        status.put("traceCode", UUID.randomUUID().toString().replace("-", ""));
        Map<String, Object> body = new LinkedHashMap<>();
        if (data != null) {
            body.put("data", data);
        }
        body.put("status", status);
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    private static String message(int code) {
        return switch (code) {
            case OK -> "Success";
            case BAD_PARAMETER -> "Bad parameter";
            case TRANSACTION_ID_DUPLICATED -> "Transaction ID duplicated";
            case INSUFFICIENT_BALANCE -> "Insufficient balance";
            case ACCOUNT_NOT_EXIST -> "Account not exist";
            case ROUND_ID_DUPLICATED -> "Round ID duplicated";
            case UNAUTHORIZED -> "Unauthorized";
            case NOT_FOUND -> "Not Found";
            default -> "Something wrong";
        };
    }

    private static String language(String language) {
        if (language == null) {
            return "en-US";
        }
        String lang = language.toLowerCase(Locale.ROOT);
        return switch (lang.length() > 2 ? lang.substring(0, 2) : lang) {
            case "zh" -> "zh-CN";
            case "th" -> "th-TH";
            case "id" -> "id-ID";
            case "vi" -> "vi-VN";
            default -> "en-US";
        };
    }
}
