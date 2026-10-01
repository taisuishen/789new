package com.bingo789.game.adapter.we;

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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * WE live casino / sportsbook seamless wallet: callbacks {@code POST /callback/WE/<validate|balance|debit|credit|
 * rollback|resettlement|netcheck>}, application/x-www-form-urlencoded; {@code credit} and {@code resettlement} carry a
 * form field {@code data} = JSON array of items. JSON replies; the HTTP status equals the body {@code code} (200 on
 * success, {@code {"code":..,"error":..}} otherwise). Amounts and balances are integer cents.
 * <p>
 * Auth: no signature. Every call except netcheck carries the merchant credentials {@code operatorID} + {@code appSecret}
 * in clear (in each {@code data} item for credit / resettlement), compared in constant time with operator-id / secret.
 * {@code validate}, {@code balance} and {@code debit} also carry the player's game {@code token} and are a
 * {@link WalletCommand.Session}; their {@code playerID} must belong to the token. Credit / rollback / resettlement
 * identify the player by {@code playerID}.
 * <p>
 * Idempotency and rounds: {@code betID} is the key of the debit, of its credit (same id, the wallet key includes the
 * type) and the round id (credits carry no gameRoundID). A credit batch is a {@link WalletCommand.Batch} of payouts,
 * each idempotent, so WE's retry of a partly applied batch completes it. Rollback ({@code type=cancel}) reverses the
 * bet; with {@code amount=0} WE asks for "stake minus win", i.e. the bet and its credit are both reversed (no credit to
 * reverse = success). A resettlement item is a signed ADJUST keyed {@code betID:resettleTime}, so several resettlements
 * of one bet are possible; a zero delta changes nothing and answers the balance. Duplicates answer 409 as WE specifies
 * (a credit batch answers 409 only when every item was a duplicate, like the old aggregation).
 * <p>
 * Deliberate deviations from the old integration: the rollback guard is fixed (the old code rejected every rollback
 * that was NOT preceded by a resettlement); all resettlement items are applied (the old code applied item 0 only); a
 * rollback of a bet we never received succeeds (tombstone) instead of 410; a rollback with an explicit amount refunds
 * the stored stake (the wallet cannot refund an arbitrary amount; WE's amount is the stake); bet lookups have no
 * 2-hour window; a zero resettlement answers 200 instead of 400; a debit whose playerID is not the token's player is
 * refused (the old code ignored playerID); 11013 (account locked) is sent with HTTP 403 because 11013 is not a valid
 * HTTP status; negative credit amounts are refused (400) instead of debiting.
 * <p>
 * Gaps: a rollback after a resettlement leaves the ADJUST in place (Rollback cannot target an ADJUST). {@code jpWinAmt}
 * of a credit is informational only (included in, or paid apart from, {@code amount}; the old code did not pay it
 * either). No round query and no game catalogue API is used.
 * <p>
 * Configuration ({@code bingo.providers.WE}): {@code operator-id} = operatorID, {@code secret} = appSecret,
 * {@code base-url} = WE API host (player/launch, player/create, report/bet). Bet pull: the report is filtered by
 * settlement time and paged by offset (5000 rows); the defaults fit.
 */
@Slf4j
@Component
public class WeAdapter implements ProviderAdapter {

    static final String NAME = "WE";

    static final int OK = 200;
    static final int E_BAD_REQUEST = 400;
    static final int E_APP_SECRET = 401;
    static final int E_INSUFFICIENT_BALANCE = 402;
    static final int E_INVALID_TOKEN = 404;
    static final int E_DUPLICATE = 409;
    static final int E_CAN_NOT_CREDIT = 410;
    static final int E_UPDATE_BALANCE = 500;
    static final int E_ACCOUNT_LOCKED = 11013;

    static final int PAGE_SIZE = 5000;

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    /** Merchant credentials sent in clear: constant-time comparison with operator-id / secret. */
    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
        String action = request.action();
        if (action.equals("netcheck")) {
            return;
        }
        Map<String, String> form = Forms.body(request);
        if (action.equals("credit") || action.equals("resettlement")) {
            for (JsonNode item : items(form)) {
                checkCredentials(item.path("operatorID").asString(null), item.path("appSecret").asString(null), client);
            }
        } else {
            checkCredentials(form.get("operatorID"), form.get("appSecret"), client);
        }
    }

    private static void checkCredentials(String operatorId, String appSecret, ProviderClient client) {
        if (operatorId == null || operatorId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw CallbackException.auth("missing operatorID / appSecret");
        }
        boolean operatorOk = Hmacs.safeEquals(client.config().operatorId(), operatorId);
        boolean secretOk = Hmacs.safeEquals(client.secret(), appSecret);
        if (!operatorOk || !secretOk) {
            throw CallbackException.auth("incorrect operatorID / appSecret");
        }
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        Map<String, String> p = Forms.body(request);
        return switch (request.action()) {
            case "validate" -> new WalletCommand.Authenticate(Fields.required(p, "token"), null);
            case "balance" -> new WalletCommand.Session(Fields.required(p, "token"),
                    new WalletCommand.GetBalance(Fields.required(p, "playerID"), null));
            case "debit" -> {
                String bet = Fields.required(p, "betID");
                yield new WalletCommand.Session(Fields.required(p, "token"), new WalletCommand.Bet(Fields.required(p, "playerID"),
                        null, bet, bet, Fields.required(p, "gameID"), amount(Fields.longValue(p.get("amount"), "amount"), "amount"), false));
            }
            case "credit" -> credit(items(p));
            case "rollback" -> rollback(p);
            case "resettlement" -> resettlement(items(p));
            case "netcheck" -> new WalletCommand.Ack(null, null);
            default -> throw CallbackException.unknownAction(request.action());
        };
    }

    /** One payout per item, of one player; each settles its bet (same betID) and closes that bet's round. */
    private static WalletCommand credit(List<JsonNode> items) {
        List<WalletCommand> steps = new ArrayList<>();
        String player = samePlayer(items);
        for (JsonNode item : items) {
            String bet = text(item, "betID");
            steps.add(new WalletCommand.Payout(player, null, bet, bet, item.path("gameID").asString(null),
                    amount(cents(item, "amount"), "amount"), TxnType.PAYOUT, bet, true));
        }
        return steps.size() == 1 ? steps.getFirst() : new WalletCommand.Batch(player, null, steps);
    }

    /** amount = 0: "stake minus win", the bet and its credit are reversed; otherwise the stake alone. */
    private static WalletCommand rollback(Map<String, String> p) {
        if (!"cancel".equals(p.get("type"))) {
            throw CallbackException.badRequest("rollback type must be cancel");
        }
        String player = Fields.required(p, "playerID");
        String bet = Fields.required(p, "betID");
        WalletCommand.Rollback stake = new WalletCommand.Rollback(player, null, "cancel:" + bet, bet, TxnType.BET, bet, p.get("gameID"));
        if (Fields.longValue(p.get("amount"), "amount") != 0) {
            return stake;
        }
        WalletCommand.Rollback win = new WalletCommand.Rollback(player, null, "cancel:" + bet, bet, TxnType.PAYOUT, bet, p.get("gameID"));
        return new WalletCommand.Batch(player, null, List.of(win, stake));
    }

    /** resettleAmount is the signed delta to apply; zero deltas change nothing. */
    private static WalletCommand resettlement(List<JsonNode> items) {
        String player = samePlayer(items);
        List<WalletCommand> steps = new ArrayList<>();
        for (JsonNode item : items) {
            long delta = cents(item, "resettleAmount");
            if (delta == 0) {
                continue;
            }
            String bet = text(item, "betID");
            steps.add(new WalletCommand.Adjust(player, null, bet + ":" + text(item, "resettleTime"), bet, bet,
                    Fields.fromCents(delta), "resettlement"));
        }
        if (steps.isEmpty()) {
            return new WalletCommand.GetBalance(player, null);
        }
        return steps.size() == 1 ? steps.getFirst() : new WalletCommand.Batch(player, null, steps);
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        String action = request.action();
        boolean money = !action.equals("validate") && !action.equals("balance") && !action.equals("netcheck");
        int code = switch (outcome.code()) {
            case SUCCESS -> money && outcome.replay() ? E_DUPLICATE : OK;
            case INSUFFICIENT_FUNDS -> E_INSUFFICIENT_BALANCE;
            case INVALID_TOKEN -> E_INVALID_TOKEN;
            case PLAYER_LOCKED -> E_ACCOUNT_LOCKED;
            case PLAYER_NOT_FOUND, INVALID_REQUEST -> E_BAD_REQUEST;
            // "stake minus win" of a bet that was never credited: the stake was reversed, there is no win to reverse
            case TXN_NOT_FOUND -> action.equals("rollback") ? OK : E_CAN_NOT_CREDIT;
            case BET_NOT_FOUND, TXN_CANCELLED, BET_SETTLED -> E_CAN_NOT_CREDIT;
        };
        if (code != OK) {
            return error(code);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        switch (action) {
            case "netcheck" -> body.put("operatorID", client.config().operatorId());
            case "validate" -> {
                body.put("playerID", outcome.playerId());
                body.put("currency", outcome.currency());
                body.put("nickname", outcome.playerId());
                body.put("time", Instant.now().getEpochSecond());
                body.put("balance", Fields.toCents(outcome.balance()));
            }
            case "balance" -> {
                body.put("currency", outcome.currency());
                body.put("time", Instant.now().getEpochSecond());
                body.put("balance", Fields.toCents(outcome.balance()));
            }
            default -> {
                body.put("playerID", outcome.playerId());
                body.put("currency", outcome.currency());
                body.put("balance", Fields.toCents(outcome.balance()));
                body.put("time", Instant.now().getEpochSecond());
                if (outcome.platformTxnId() != null) {
                    body.put("refID", String.valueOf(outcome.platformTxnId()));
                }
            }
        }
        body.put("code", OK);
        return CallbackResponse.json(OK, JsonUtils.toJson(body));
    }

    /** 500 is WE's "update balance error", which WE retries; never 200. */
    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        return error(switch (error) {
            case AUTH_FAILED -> E_APP_SECRET;
            case BAD_REQUEST, UNKNOWN_ACTION -> E_BAD_REQUEST;
            case RATE_LIMITED, SYSTEM_RETRYABLE -> E_UPDATE_BALANCE;
        });
    }

    // ================================================================ outbound

    /** player/launch; a player WE does not know yet is created (player/create) and the launch repeated once. */
    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        try {
            return new LaunchView(launchUrl(c, playerId, client), null);
        } catch (HttpClientErrorException e) {
            log.info("WE launch answered {} for {}, creating the player", e.getStatusCode().value(), playerId);
            createPlayer(playerId, client);
            return new LaunchView(launchUrl(c, playerId, client), null);
        }
    }

    private static String launchUrl(LaunchCommand c, String playerId, ProviderClient client) {
        String requestTime = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("operatorID", client.config().operatorId());
        form.put("playerID", playerId);
        form.put("requestTime", requestTime);
        form.put("clientIP", c.clientIp());
        form.put("lang", language(c.language()));
        form.put("token", c.gameToken());
        String game = c.gameCode() == null ? "" : c.gameCode().strip();
        if (game.equalsIgnoreCase("WESP") || game.equalsIgnoreCase("Sportbook")) {
            form.put("category", "Sportbook");
        } else if (!game.isEmpty() && !game.equalsIgnoreCase("lobby")) {
            form.put("tableID", game);
        }
        String response = client.rest().post()
                .uri("/player/launch")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // login is the one call signed with sha256(appSecret + requestTime)
                .header("signature", Ciphers.sha256Hex(client.secret() + requestTime))
                .body(Forms.encode(form))
                .retrieve()
                .body(String.class);
        String url = response == null ? "" : JsonUtils.mapper().readTree(response).path("url").asString("");
        if (url.isBlank()) {
            throw new IllegalStateException("WE player/launch returned no url");
        }
        return url;
    }

    private static void createPlayer(String playerId, ProviderClient client) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("operatorID", client.config().operatorId());
        params.put("playerID", playerId);
        params.put("requestTime", String.valueOf(Instant.now().getEpochSecond()));
        params.put("nickname", playerId);
        try {
            post(client, "/player/create", params);
        } catch (RestClientResponseException e) {
            // e.g. the player exists already; the repeated launch decides
            log.info("WE player/create answered {} for {}", e.getStatusCode().value(), playerId);
        }
    }

    /** No catalogue API is used for WE (tables are configured in the lobby). */
    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        return List.of();
    }

    /** report/bet by settlement time; the cursor is the row offset of the next page. */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        int offset = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
        TreeMap<String, String> params = new TreeMap<>();
        params.put("startTime", String.valueOf(query.from().getEpochSecond()));
        params.put("endTime", String.valueOf(query.to().getEpochSecond()));
        params.put("limit", String.valueOf(PAGE_SIZE));
        params.put("requestTime", String.valueOf(Instant.now().getEpochSecond()));
        params.put("operatorID", client.config().operatorId());
        params.put("isSettlementTime", "true");
        if (offset > 0) {
            params.put("offset", String.valueOf(offset));
        }
        JsonNode root = JsonUtils.mapper().readTree(post(client, "/report/bet", params));
        String currency = client.config().currencies().isEmpty() ? null : client.config().currencies().getFirst();
        boolean more = root.path("data").size() >= PAGE_SIZE;
        return new BetPullPage(parseReport(root, client.providerCode(), currency), more ? String.valueOf(offset + PAGE_SIZE) : null, more);
    }

    /**
     * Records carry no currency (the merchant account's currency applies); amounts are cents; {@code winlossAmount} is
     * net, so the payout is {@code betAmount + winlossAmount}. new / processing = OPEN, complete = SETTLED,
     * cancel = CANCELLED. WE may return player ids in another case than we sent.
     */
    static List<ProviderBetRecordView> parseReport(JsonNode root, String providerCode, String currency) {
        List<ProviderBetRecordView> records = new ArrayList<>();
        for (JsonNode r : root.path("data")) {
            String player = r.path("playerID").asString("").toLowerCase(Locale.ROOT);
            Long userId = PlayerIds.tryDecode(player);
            if (userId == null) {
                log.warn("WE bet {} belongs to unknown player {}", r.path("betID").asString(""), player);
                continue;
            }
            String status = switch (r.path("betStatus").asString("")) {
                case "complete" -> "SETTLED";
                case "cancel" -> "CANCELLED";
                default -> "OPEN";
            };
            BigDecimal bet = Fields.fromCents(r.path("betAmount").asLong());
            BigDecimal payout = status.equals("SETTLED") ? bet.add(Fields.fromCents(r.path("winlossAmount").asLong())) : BigDecimal.ZERO;
            long settled = r.path("settlementTime").asLong(0);
            String betId = r.path("betID").asString();
            records.add(new ProviderBetRecordView(providerCode, betId, betId, userId, currency, r.path("gameType").asString(null),
                    bet, payout, status, Instant.ofEpochSecond(r.path("betDateTime").asLong()),
                    settled > 0 ? Instant.ofEpochSecond(settled) : null));
        }
        return records;
    }

    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    /** {@code signature} header = md5 of the parameter values in key order, appSecret included (not sent). */
    static String signature(Map<String, String> params, String appSecret) {
        TreeMap<String, String> signed = new TreeMap<>(params);
        signed.put("appSecret", appSecret);
        return Ciphers.md5Hex(String.join("", signed.values()));
    }

    private static String post(ProviderClient client, String path, Map<String, String> params) {
        return client.rest().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("signature", signature(params, client.secret()))
                .body(Forms.encode(new TreeMap<>(params)))
                .retrieve()
                .body(String.class);
    }

    private static List<JsonNode> items(Map<String, String> form) {
        JsonNode data;
        try {
            data = JsonUtils.mapper().readTree(Fields.required(form, "data"));
        } catch (JacksonException e) {
            throw CallbackException.badRequest("data is not json");
        }
        if (!data.isArray() || data.isEmpty()) {
            throw CallbackException.badRequest("data must be a non-empty array");
        }
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        return items;
    }

    private static String samePlayer(List<JsonNode> items) {
        String player = text(items.getFirst(), "playerID");
        for (JsonNode item : items) {
            if (!player.equals(text(item, "playerID"))) {
                throw CallbackException.badRequest("items of several players in one call");
            }
        }
        return player;
    }

    private static String text(JsonNode item, String field) {
        return Fields.required(item.path(field).asString(null), field);
    }

    private static long cents(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw CallbackException.badRequest(field + " is required");
        }
        try {
            return value.asLong();
        } catch (JacksonException e) {
            throw CallbackException.badRequest(field + " is not an integer");
        }
    }

    private static BigDecimal amount(long cents, String field) {
        if (cents < 0) {
            throw CallbackException.badRequest(field + " must not be negative");
        }
        return Fields.fromCents(cents);
    }

    private static CallbackResponse error(int code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", description(code));
        return CallbackResponse.json(code == E_ACCOUNT_LOCKED ? 403 : code, JsonUtils.toJson(body));
    }

    private static String description(int code) {
        return switch (code) {
            case E_BAD_REQUEST -> "Bad Request";
            case E_APP_SECRET -> "Incorrect appSecret";
            case E_INSUFFICIENT_BALANCE -> "Insufficient balance";
            case E_INVALID_TOKEN -> "Invalid Token";
            case E_DUPLICATE -> "Duplicate transaction";
            case E_CAN_NOT_CREDIT -> "Can't credit";
            case E_ACCOUNT_LOCKED -> "User account locked";
            default -> "Update balance error";
        };
    }

    private static String language(String language) {
        if (language == null) {
            return "en";
        }
        String lang = language.replace('_', '-').toLowerCase(Locale.ROOT);
        if (lang.equals("zh-tw") || lang.equals("zh-hk") || lang.equals("zh-hant")) {
            return "zh";
        }
        return switch (lang.length() > 2 ? lang.substring(0, 2) : lang) {
            case "zh" -> "cn";
            case "th" -> "th";
            case "vi" -> "vi";
            case "ko" -> "ko";
            case "ja" -> "ja";
            case "id" -> "id";
            default -> "en";
        };
    }
}
