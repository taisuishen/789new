package com.bingo789.game.adapter.cq9;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CQ9 seamless wallet. CQ9 appends fixed paths to the registered base URL {@code /callback/CQ9}:
 * {@code GET player/check/{account}}, {@code GET transaction/balance/{account}}, {@code POST
 * transaction/game/{bet|endround|rollout|rollin|debit|credit|refund}} and {@code POST transaction/user/payoff}
 * (application/x-www-form-urlencoded). Replies are JSON {@code {"data":..,"status":{"code","message","datetime"}}},
 * always HTTP 200; amounts are decimals in currency units.
 * <p>
 * Auth: header {@code wtoken} (static merchant credential), compared in constant time; no signature, no timestamp.
 * Requests carry no currency: the provider's first configured currency is used, so one CQ9 agent (one currency, one
 * API token) is one provider code.
 * <p>
 * Idempotency: {@code mtcode} identifies every money call. bet and rollout are bets of {@code roundid}; endround pays
 * every {@code data[]} item as its own payout (one batch, so a retry completes a partly applied settlement; free-ticket
 * rounds as FREE_PAYOUT, which needs no bet); rollin is the payout of a rollout round and needs its bet (BET_NOT_FOUND
 * = 1014, CQ9 retries); debit / credit (补扣 / 补派) are signed adjustments; refund reverses the bet with that mtcode;
 * payoff is a promo payout of round {@code promo:<promoid>}. Duplicates answer success with the current balance.
 * <p>
 * Deliberate deviations from the old code: bets are keyed on mtcode (the old per-round ledger key answered a second
 * bet of a round "success" without debiting it); a retried bet answers its original success even if the balance has
 * dropped since (old: 1005); a refund of an unknown mtcode leaves a tombstone, so the late bet is refused (1005
 * "Transaction cancelled") instead of debited; an endround item without a live bet in the round answers 1014 while
 * {@code require-bet-for-payout} is true (old paid regardless); no 2 h / 7 d lookback windows; debit / credit no longer
 * check that the round has a bet (Adjust has no such check); balances are rounded DOWN at {@code balance-scale} (old
 * HALF_DOWN at 4 decimals); a GET without wtoken answers 1003 instead of HTTP 400. {@code takeall} is NOT supported and
 * answered 1002: it debits the whole, unknown balance and must echo it, which no wallet command can express - configure
 * the CQ9 agent for rollout / rollin. Bet pull pages by the cumulative row count against {@code TotalSize} (the old
 * loop always re-sent page 1) and pulls sports-lottery records from the second endpoint.
 * <p>
 * Configuration ({@code bingo.providers.CQ9}): {@code secret} = wtoken, {@code secrets.apiToken} = the agent's API
 * token (Authorization header of every outbound call), {@code base-url} = CQ9 API host, {@code currencies} = the agent's
 * one currency (required: callbacks carry none). Bet pull: the defaults fit (CQ9 serves settled rows immediately; open
 * rounds come back as OPEN and are upserted when a later overlapping window returns them complete).
 */
@Slf4j
@Component
public class Cq9Adapter implements ProviderAdapter {

    static final String NAME = "CQ9";

    private static final String CHECK = "player/check/";
    private static final String BALANCE = "transaction/balance/";
    /** CQ9 status of a launch for an account it does not know yet. */
    private static final String ACCOUNT_NOT_FOUND = "14";
    /** CQ9 status "no data" of the history endpoints. */
    private static final String NO_DATA = "8";
    private static final ZoneOffset CQ9_ZONE = ZoneOffset.ofHours(-4);
    private static final DateTimeFormatter STATUS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX");
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** Form fields that must be RFC3339 when present; a bad one is answered 1004 instead of 1003. */
    private static final List<String> TIME_FIELDS = List.of("eventTime", "createTime");
    /** Slot / fish / table / arcade history, then sports lottery; the cursor walks them in this order. */
    private static final List<String> HISTORY_PATHS = List.of("/gameboy/order/view", "/gameboy/order/view/lotto");
    private static final int LOTTO = 1;

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
        String wtoken = request.header("wtoken");
        if (wtoken == null || wtoken.isBlank()) {
            throw CallbackException.auth("missing wtoken");
        }
        if (!Hmacs.safeEquals(client.secret(), wtoken)) {
            throw CallbackException.auth("wtoken mismatch");
        }
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        String action = request.action();
        if (action.startsWith(CHECK)) {
            return new WalletCommand.GetBalance(account(action, CHECK), null);
        }
        if (action.startsWith(BALANCE)) {
            return new WalletCommand.GetBalance(account(action, BALANCE), null);
        }
        Map<String, String> p = Forms.body(request);
        checkTimes(p);
        return switch (action) {
            case "transaction/game/bet", "transaction/game/rollout" -> new WalletCommand.Bet(Fields.required(p, "account"),
                    null, Fields.required(p, "mtcode"), Fields.required(p, "roundid"), p.get("gamecode"),
                    positive(p.get("amount"), "amount"), false);
            case "transaction/game/endround" -> endRound(p);
            case "transaction/game/rollin" -> new WalletCommand.Payout(Fields.required(p, "account"), null,
                    Fields.required(p, "mtcode"), Fields.required(p, "roundid"), p.get("gamecode"),
                    nonNegative(p.get("amount"), "amount"), TxnType.PAYOUT, null, true);
            case "transaction/game/debit" -> new WalletCommand.Adjust(Fields.required(p, "account"), null,
                    Fields.required(p, "mtcode"), null, Fields.required(p, "roundid"),
                    positive(p.get("amount"), "amount").negate(), "CQ9 debit");
            case "transaction/game/credit" -> new WalletCommand.Adjust(Fields.required(p, "account"), null,
                    Fields.required(p, "mtcode"), null, Fields.required(p, "roundid"),
                    positive(p.get("amount"), "amount"), "CQ9 credit");
            // the refund names the mtcode of the bet it cancels; the whole stake is returned
            case "transaction/game/refund" -> new WalletCommand.Rollback(Fields.required(p, "account"), null,
                    "refund:" + Fields.required(p, "mtcode"), p.get("mtcode"), TxnType.BET, null, null);
            case "transaction/user/payoff" -> new WalletCommand.Payout(Fields.required(p, "account"), null,
                    Fields.required(p, "mtcode"), "promo:" + Fields.required(p, "promoid"), null,
                    positive(p.get("amount"), "amount"), TxnType.PROMO_PAYOUT, null, true);
            // takeall (debit the whole balance) is not expressible, see the class comment
            default -> throw CallbackException.unknownAction(action);
        };
    }

    /** One payout per data[] item; the last one closes the round. */
    private static WalletCommand endRound(Map<String, String> p) {
        String account = Fields.required(p, "account");
        String round = Fields.required(p, "roundid");
        TxnType type = Boolean.parseBoolean(p.get("freeticket")) ? TxnType.FREE_PAYOUT : TxnType.PAYOUT;
        JsonNode items = endRoundData(Fields.required(p, "data"));
        List<WalletCommand> steps = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            steps.add(new WalletCommand.Payout(account, null, Fields.required(text(item, "mtcode"), "data.mtcode"), round,
                    p.get("gamecode"), nonNegative(text(item, "amount"), "data.amount"), type, null, i == items.size() - 1));
        }
        return steps.size() == 1 ? steps.getFirst() : new WalletCommand.Batch(account, null, steps);
    }

    /** The data field is a JSON array sent as text, sometimes quoted or with escaped quotes (old: unescapeEcmaScript). */
    static JsonNode endRoundData(String raw) {
        JsonNode node = readJson(raw);
        if (node != null && node.isString()) {
            node = readJson(node.stringValue());
        }
        if (node == null && raw.contains("\\\"")) {
            node = readJson(raw.replace("\\\"", "\""));
        }
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw CallbackException.badRequest("data is not a non-empty JSON array");
        }
        return node;
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        if (request.action().startsWith(CHECK)
                && (outcome.isSuccess() || outcome.code() == CommandOutcome.Code.PLAYER_NOT_FOUND)) {
            // check player answers whether the account is ours, it is not an error
            return reply("0", "Success", outcome.isSuccess());
        }
        // a rollback without anything to reverse has nothing left to do: success, CQ9 stops retrying
        boolean nothingToReverse = outcome.code() == CommandOutcome.Code.TXN_NOT_FOUND && command instanceof WalletCommand.Rollback;
        if (outcome.isSuccess() || nothingToReverse) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("balance", number(Fields.balance(outcome.balance(), client.config().balanceScale())));
            data.put("currency", outcome.currency());
            return reply("0", "Success", data);
        }
        return switch (outcome.code()) {
            case INSUFFICIENT_FUNDS, PLAYER_LOCKED -> reply("1005", "Insufficient Balance", null);
            // a bet whose mtcode was refunded before it arrived: final, CQ9 must not retry it
            case TXN_CANCELLED, BET_SETTLED -> reply("1005", "Transaction cancelled", null);
            case PLAYER_NOT_FOUND -> reply("1006", "Player not found", null);
            // rollin / endround before its bet: CQ9 retries, by then the bet may have arrived
            case BET_NOT_FOUND, TXN_NOT_FOUND -> reply("1014", "Record not found", null);
            case INVALID_TOKEN, INVALID_REQUEST, SUCCESS -> reply("1003", "Parameter error", null);
        };
    }

    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        return switch (error) {
            case AUTH_FAILED -> reply("1003", "Missing wtoken", null);
            case BAD_REQUEST -> hasBadTime(request) ? reply("1004", "Time format error", null) : reply("1003", "Parameter error", null);
            case UNKNOWN_ACTION -> reply("1002", "Invalid action", null);
            case RATE_LIMITED, SYSTEM_RETRYABLE -> reply("1100", "Server error", null);
        };
    }

    // ================================================================ outbound

    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        if (c.demo()) {
            throw new IllegalArgumentException("CQ9 seamless launch has no demo mode");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("account", playerId);
        params.put("gamehall", "CQ9");
        params.put("gamecode", c.gameCode());
        params.put("gameplat", "MOBILE".equalsIgnoreCase(c.platform()) ? "mobile" : "web");
        params.put("lang", language(c.language()));
        JsonNode response = post(client, "/gameboy/player/sw/gamelink", params);
        if (ACCOUNT_NOT_FOUND.equals(code(response))) {
            // first launch of this player: create the account, then retry once. The password is never used in seamless
            // mode (CQ9 identifies the player by account), so a random one is enough
            Map<String, String> create = new LinkedHashMap<>();
            create.put("account", playerId);
            create.put("password", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            requireOk(post(client, "/gameboy/player", create), "player");
            response = post(client, "/gameboy/player/sw/gamelink", params);
        }
        requireOk(response, "sw/gamelink");
        return new LaunchView(response.path("data").path("url").asString(), null);
    }

    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        JsonNode response = get(client, "/gameboy/game/list/CQ9", Map.of());
        requireOk(response, "game/list");
        List<ProviderGameView> games = new ArrayList<>();
        for (JsonNode g : response.path("data")) {
            if (g.path("status").isBoolean() && !g.path("status").asBoolean()) {
                continue;
            }
            String plat = g.path("gameplat").asString("");
            games.add(new ProviderGameView(client.providerCode(), g.path("gamecode").asString(), gameName(g),
                    g.path("gametype").asString(null), null, null,
                    !plat.equalsIgnoreCase("web"), !plat.equalsIgnoreCase("mobile")));
        }
        return games;
    }

    /**
     * {@code /gameboy/order/view} then {@code /gameboy/order/view/lotto}, page by page. Cursor
     * {@code <endpoint>:<page>:<rows seen>}; an endpoint is done when the rows seen reach its {@code TotalSize} (the
     * count works whatever page size CQ9 applies).
     */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        int endpoint = 0;
        int page = 1;
        long seen = 0;
        if (query.cursor() != null) {
            String[] parts = query.cursor().split(":");
            endpoint = Integer.parseInt(parts[0]);
            page = Integer.parseInt(parts[1]);
            seen = Long.parseLong(parts[2]);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("starttime", queryTime(query.from()));
        params.put("endtime", queryTime(query.to()));
        params.put("page", String.valueOf(page));
        params.put("pageSize", String.valueOf(query.pageSize() > 0 ? query.pageSize() : 1000));
        JsonNode response = get(client, HISTORY_PATHS.get(endpoint), params);
        String code = code(response);
        if (!"0".equals(code) && !NO_DATA.equals(code)) {
            throw new IllegalStateException("CQ9 " + HISTORY_PATHS.get(endpoint) + " failed: " + code + " "
                    + response.path("status").path("message").asString(""));
        }
        JsonNode rows = response.path("data").path("Data");
        List<ProviderBetRecordView> records = parseRecords(rows, client.providerCode(), endpoint == LOTTO);
        seen += rows.size();
        String next;
        if (!rows.isEmpty() && seen < response.path("data").path("TotalSize").asLong(0)) {
            next = endpoint + ":" + (page + 1) + ":" + seen;
        } else if (endpoint + 1 < HISTORY_PATHS.size()) {
            next = (endpoint + 1) + ":1:0";
        } else {
            next = null;
        }
        return new BetPullPage(records, next, next != null);
    }

    /** Rows of either endpoint; unsettled rounds are OPEN (lotto: {@code wins} is null until drawn). */
    static List<ProviderBetRecordView> parseRecords(JsonNode rows, String providerCode, boolean lotto) {
        List<ProviderBetRecordView> records = new ArrayList<>();
        for (JsonNode r : rows) {
            String betId = text(r, lotto ? "roundid" : "round");
            Long userId = PlayerIds.tryDecode(text(r, "account"));
            if (userId == null) {
                log.warn("CQ9 bet {} belongs to unknown player {}", betId, text(r, "account"));
                continue;
            }
            if (lotto) {
                BigDecimal wins = decimal(r, "wins");
                BigDecimal bets = decimal(r, "bets") != null ? decimal(r, "bets") : decimal(r, "betamount");
                records.add(new ProviderBetRecordView(providerCode, betId, betId, userId, text(r, "currency"),
                        text(r, "gamecode"), bets, wins == null ? BigDecimal.ZERO : wins, wins == null ? "OPEN" : "SETTLED",
                        time(text(r, "bettime")), wins == null ? null : time(text(r, "finaltime"))));
            } else {
                boolean complete = "complete".equals(text(r, "status"));
                BigDecimal win = decimal(r, "win");
                records.add(new ProviderBetRecordView(providerCode, betId, betId, userId, text(r, "currency"),
                        text(r, "gamecode"), decimal(r, "bet"), win == null ? BigDecimal.ZERO : win,
                        complete ? "SETTLED" : "OPEN", time(text(r, "bettime")), complete ? time(text(r, "createtime")) : null));
            }
        }
        return records;
    }

    /** No round query in the CQ9 seamless API: open rounds are closed by endround / refund, or escalated by the resolver. */
    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    private static String account(String action, String prefix) {
        return Fields.required(Forms.decode(action.substring(prefix.length())), "account");
    }

    private static void checkTimes(Map<String, String> p) {
        if (hasBadTime(p)) {
            throw CallbackException.badRequest("time format error");
        }
    }

    private static boolean hasBadTime(CallbackRequest request) {
        return request.body().length > 0 && hasBadTime(Forms.body(request));
    }

    private static boolean hasBadTime(Map<String, String> p) {
        for (String field : TIME_FIELDS) {
            String value = p.get(field);
            if (value != null && !value.isBlank()) {
                try {
                    OffsetDateTime.parse(value.strip());
                } catch (DateTimeParseException e) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BigDecimal positive(String value, String field) {
        BigDecimal amount = Fields.amount(value, field);
        if (amount.signum() <= 0) {
            throw CallbackException.badRequest(field + " must be greater than 0");
        }
        return amount;
    }

    private static BigDecimal nonNegative(String value, String field) {
        BigDecimal amount = Fields.amount(value, field);
        if (amount.signum() < 0) {
            throw CallbackException.badRequest(field + " must not be negative");
        }
        return amount;
    }

    private static JsonNode post(ProviderClient client, String path, Map<String, String> params) {
        String response = client.rest().post()
                .uri(path)
                .header("Authorization", client.secret("apiToken"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Forms.encode(params))
                .retrieve()
                .body(String.class);
        return JsonUtils.mapper().readTree(response);
    }

    private static JsonNode get(ProviderClient client, String path, Map<String, String> query) {
        String response = client.rest().get()
                .uri(builder -> {
                    builder.path(path);
                    for (Map.Entry<String, String> param : query.entrySet()) {
                        builder.queryParam(param.getKey(), param.getValue());
                    }
                    return builder.build();
                })
                .header("Authorization", client.secret("apiToken"))
                .retrieve()
                .body(String.class);
        return JsonUtils.mapper().readTree(response);
    }

    private static String code(JsonNode response) {
        return response.path("status").path("code").asString("");
    }

    private static void requireOk(JsonNode response, String call) {
        if (!"0".equals(code(response))) {
            throw new IllegalStateException("CQ9 " + call + " failed: " + code(response) + " "
                    + response.path("status").path("message").asString(""));
        }
    }

    private static String gameName(JsonNode game) {
        for (JsonNode name : game.path("nameset")) {
            if (name.path("lang").asString("").toLowerCase(Locale.ROOT).startsWith("en")) {
                return name.path("name").asString();
            }
        }
        return game.path("gamename").asString(game.path("gamecode").asString());
    }

    /** CQ9 language codes: {@code en}, {@code zh-cn}, {@code th}, {@code vn} ...; English by default. */
    static String language(String language) {
        if (language == null || language.isBlank()) {
            return "en";
        }
        String lang = language.toLowerCase(Locale.ROOT).replace('_', '-');
        if (lang.startsWith("zh")) {
            return "zh-cn";
        }
        if (lang.startsWith("vi")) {
            return "vn";
        }
        return lang.length() > 2 ? lang.substring(0, 2) : lang;
    }

    private static String queryTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).atOffset(CQ9_ZONE).format(QUERY_TIME);
    }

    private static Instant time(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value.strip()).toInstant();
    }

    private static JsonNode readJson(String value) {
        try {
            return JsonUtils.mapper().readTree(value);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Text of a scalar field (numbers as written), null when absent or JSON null. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isValueNode() ? value.asString() : value.toString();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : new BigDecimal(value.strip());
    }

    private static BigDecimal number(String value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static CallbackResponse reply(String code, String message, Object data) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("code", code);
        status.put("message", message);
        status.put("datetime", OffsetDateTime.now(CQ9_ZONE).format(STATUS_TIME));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("status", status);
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }
}
