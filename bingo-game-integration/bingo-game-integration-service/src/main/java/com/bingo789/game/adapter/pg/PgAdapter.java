package com.bingo789.game.adapter.pg;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PG Soft seamless wallet: callbacks {@code POST /callback/PG/<VerifySession | Cash/Get | Cash/TransferInOut |
 * Cash/Adjustment | Cash/UpdateBetDetail>} (PG appends {@code ?trace_id=}, ignored), application/x-www-form-urlencoded
 * with snake_case names, JSON replies {@code {"data":{...},"error":null}} or {@code {"data":null,"error":{"code","message"}}},
 * always HTTP 200.
 * <p>
 * Auth: every call carries {@code operator_token} + {@code secret_key}, compared in constant time with the configured
 * account (no hash, no timestamp). The launch session {@code operator_player_session} is our game token (the old
 * {@code platform-PG-player-currency-loginToken} string is gone): VerifySession authenticates it, Cash/Get and normal
 * TransferInOut calls run on it ({@link WalletCommand.Session}, which also checks that {@code player_name} owns it).
 * Resends ({@code is_validate_bet}) and game-state adjustments ({@code is_adjustment}) skip the session, as PG requires
 * (the session may have ended), and so do Cash/Adjustment and calls without a session.
 * <p>
 * Ids: TransferInOut is bet + win of one spin in one call: BetAndPayout with bet id = payout id =
 * {@code transaction_id} without its last segment ({@code {BetId}-{ParentBetId}-{TransactionType}}, as the old code keyed
 * it: resends that differ only in the balance id stay duplicates), round = {@code parent_bet_id}, closed by
 * {@code is_round_end} (true when absent). Cash/Adjustment is a signed Adjust keyed by {@code adjustment_transaction_id}.
 * Cash/UpdateBetDetail moves no money (Ack). Amounts are decimals; {@code transfer_amount} must equal win - bet and
 * {@code real_transfer_amount} must equal {@code transfer_amount} (no currency conversion). Duplicates are answered
 * like the first success, with the current balance.
 * <p>
 * Deliberate deviations from the old code: one credential set per provider code (the old code accepted any enabled PG
 * firm_config: a second line is another provider code); the session check applies whenever neither resend flag is
 * true (old: skipped when a flag was missing) and an invalid or expired session is 1034 (old: 1200, retried by PG);
 * idempotency is durable (old: 1 h Redis marker, a resend after that moved money again) and the balance id is cut
 * with lastIndexOf (old: replaceAll could also cut other segments; a malformed id was an HTTP 500); bet and win are
 * atomic (old: a failed credit after the debit returned 3033 and the resend was answered as a duplicate, the win was
 * never paid); {@code is_round_end} is honoured; balances are rounded DOWN (old: HALF_DOWN could round up); amount
 * mismatches are 1034 (old: 3073 / 3107); an unknown player is 3004 (old: 1034); UpdateBetDetail answers
 * {@code is_success} (old: serialized as {@code _success}); the bet history uses the time-range API instead of the
 * row_version cursor, because the pull job works in time windows.
 * <p>
 * Framework gaps: launch builds PG's direct game URL, because LaunchView can only carry a URL while
 * GetLaunchURLHTML (used by the old code) returns an HTML page that the old code cached and served from its own jump
 * domain; CommandOutcome has no balance before, so Cash/Adjustment reports {@code balance_after - transfer_amount}
 * (both the current balance on a replay); BetAndPayout has no payout type (free-game and bonus wallet transfers are
 * recorded as PAYOUT); {@code adjustment_transaction_id} may be 200 characters (the gateway stores ids above 128 as
 * their SHA-256).
 * <p>
 * Configuration ({@code bingo.providers.PG}): {@code operator-id} = operator_token, {@code secret} = secret_key,
 * {@code base-url} = PG API domain (e.g. {@code https://api.pg-bo.me/external}), settings {@code historyUrl} (history
 * domain of GetHistoryForSpecificTimeRange, e.g. {@code .../external-datagrabber}) and {@code launchUrl} (game domain
 * of the launch URL), {@code currencies} = the account currency. Bet pull: PG publishes history 3-5 min late, so
 * {@code bingo.bet-record.pull.providers.PG.settle-delay: 5m}; {@code page-size: 5000} (PG accepts 1500-5000).
 */
@Slf4j
@Component
public class PgAdapter implements ProviderAdapter {

    static final String NAME = "PG";

    private static final int MIN_HISTORY_PAGE = 1_500;
    private static final int MAX_HISTORY_PAGE = 5_000;

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
        Map<String, String> p = Forms.body(request);
        boolean token = Hmacs.safeEquals(client.config().operatorId(), p.get("operator_token"));
        boolean secret = Hmacs.safeEquals(client.secret(), p.get("secret_key"));
        if (!(token & secret)) {
            throw CallbackException.auth("operator_token / secret_key mismatch");
        }
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        Map<String, String> p = Forms.body(request);
        return switch (action(request)) {
            case "verifysession" -> new WalletCommand.Authenticate(Fields.required(p, "operator_player_session"), null);
            case "cash/get" -> new WalletCommand.Session(Fields.required(p, "operator_player_session"),
                    new WalletCommand.GetBalance(Fields.required(p, "player_name"), null));
            case "cash/transferinout" -> transferInOut(p);
            case "cash/adjustment" -> adjustment(p);
            case "cash/updatebetdetail" -> new WalletCommand.Ack(null, null);
            default -> throw CallbackException.unknownAction(request.action());
        };
    }

    private static WalletCommand transferInOut(Map<String, String> p) {
        String player = Fields.required(p, "player_name");
        String currency = Fields.required(p, "currency_code");
        BigDecimal bet = Fields.amount(p.get("bet_amount"), "bet_amount");
        BigDecimal win = Fields.amount(p.get("win_amount"), "win_amount");
        BigDecimal transfer = Fields.amount(p.get("transfer_amount"), "transfer_amount");
        if (bet.signum() < 0 || win.signum() < 0) {
            throw CallbackException.badRequest("bet_amount and win_amount must not be negative");
        }
        if (win.subtract(bet).compareTo(transfer) != 0) {
            throw CallbackException.badRequest("transfer_amount is not win_amount - bet_amount");
        }
        if (Fields.amount(p.get("real_transfer_amount"), "real_transfer_amount").compareTo(transfer) != 0) {
            throw CallbackException.badRequest("real_transfer_amount differs from transfer_amount");
        }
        // echoed in the reply: validated before any money moves
        Fields.longValue(p.get("updated_time"), "updated_time");
        String txnId = transactionKey(Fields.required(p, "transaction_id"));
        WalletCommand.BetAndPayout spin = new WalletCommand.BetAndPayout(player, currency, txnId, txnId,
                Fields.required(p, "parent_bet_id"), p.get("game_id"), bet, win, !"false".equalsIgnoreCase(p.get("is_round_end")));
        String session = p.get("operator_player_session");
        boolean resent = "true".equalsIgnoreCase(p.get("is_validate_bet")) || "true".equalsIgnoreCase(p.get("is_adjustment"));
        return resent || session == null || session.isBlank() ? spin : new WalletCommand.Session(session, spin);
    }

    /** Tournament / promotion / external adjustment; negative amounts debit the player. */
    private static WalletCommand adjustment(Map<String, String> p) {
        BigDecimal amount = Fields.amount(p.get("transfer_amount"), "transfer_amount");
        if (Fields.amount(p.get("real_transfer_amount"), "real_transfer_amount").compareTo(amount) != 0) {
            throw CallbackException.badRequest("real_transfer_amount differs from transfer_amount");
        }
        Fields.longValue(p.get("adjustment_time"), "adjustment_time");
        return new WalletCommand.Adjust(Fields.required(p, "player_name"), Fields.required(p, "currency_code"),
                Fields.required(p, "adjustment_transaction_id"), p.get("adjustment_id"), null, amount,
                "PG adjustment " + p.getOrDefault("transaction_type", ""));
    }

    /** {@code {BetId}-{ParentBetId}-{TransactionType}-{BalanceId}} without the balance id. */
    static String transactionKey(String transactionId) {
        return transactionId.split("-").length >= 4 ? transactionId.substring(0, transactionId.lastIndexOf('-')) : transactionId;
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        String error = switch (outcome.code()) {
            case SUCCESS -> null;
            case INSUFFICIENT_FUNDS -> "3202";
            case INVALID_TOKEN, INVALID_REQUEST -> "1034";
            case PLAYER_NOT_FOUND -> "3004";
            case PLAYER_LOCKED -> "3073";
            case BET_NOT_FOUND, TXN_NOT_FOUND, TXN_CANCELLED, BET_SETTLED -> "3021";
        };
        if (error != null) {
            return error(error);
        }
        Map<String, String> p = Forms.body(request);
        int scale = client.config().balanceScale();
        Map<String, Object> data = new LinkedHashMap<>();
        switch (action(request)) {
            case "verifysession" -> {
                data.put("player_name", outcome.playerId());
                data.put("nickname", outcome.playerId());
                data.put("currency", outcome.currency());
            }
            case "cash/get" -> {
                data.put("currency_code", outcome.currency());
                data.put("balance_amount", balance(outcome.balance(), scale));
                data.put("updated_time", request.receivedAt().toEpochMilli());
            }
            // amounts and times were validated by parse: echoing them cannot fail after the money moved
            case "cash/transferinout" -> {
                data.put("currency_code", outcome.currency());
                data.put("balance_amount", balance(outcome.balance(), scale));
                data.put("updated_time", millis(p.get("updated_time"), request));
                data.put("real_transfer_amount", new BigDecimal(p.get("real_transfer_amount").strip()));
            }
            case "cash/adjustment" -> {
                BigDecimal amount = new BigDecimal(p.get("transfer_amount").strip());
                BigDecimal after = outcome.balance();
                BigDecimal before = outcome.replay() || after == null ? after : after.subtract(amount);
                data.put("adjust_amount", new BigDecimal(p.get("real_transfer_amount").strip()));
                data.put("balance_before", balance(before, scale));
                data.put("balance_after", balance(after, scale));
                data.put("updated_time", millis(p.get("adjustment_time"), request));
                data.put("real_transfer_amount", amount);
            }
            default -> data.put("is_success", true);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("error", null);
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        return switch (error) {
            case AUTH_FAILED, BAD_REQUEST, UNKNOWN_ACTION -> error("1034");
            // PG retries / resends on 1200
            case RATE_LIMITED, SYSTEM_RETRYABLE -> error("1200");
        };
    }

    // ================================================================ outbound

    /** PG's direct launch URL: {@code <launchUrl>/<gameId>/index.html?btt=1&ot=<operator_token>&ops=<game token>&l=<lang>}. */
    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        if (c.demo()) {
            throw new IllegalArgumentException("PG demo play is not supported");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("btt", "1");
        query.put("ot", client.config().operatorId());
        query.put("ops", c.gameToken());
        query.put("l", c.language() == null ? "en" : c.language());
        return new LaunchView(stripSlash(client.setting("launchUrl")) + "/" + c.gameCode() + "/index.html?" + Forms.encode(query), null);
    }

    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        Map<String, String> form = credentials(client);
        form.put("currency", accountCurrency(client));
        form.put("language", "en-us");
        form.put("status", "1");
        JsonNode response = JsonUtils.mapper().readTree(post(client, "/Game/v2/Get", form));
        requireOk(response, "Game/v2/Get");
        List<ProviderGameView> games = new ArrayList<>();
        for (JsonNode g : response.path("data")) {
            String gameId = g.path("gameId").asString(null);
            if (gameId != null && !gameId.isBlank()) {
                games.add(new ProviderGameView(client.providerCode(), gameId, g.path("gameName").asString(gameId), "Slot",
                        null, null, true, true));
            }
        }
        return games;
    }

    /**
     * GetHistoryForSpecificTimeRange: completed hands whose end time lies in [from_time, to_time], at most
     * {@code count} per call, ordered by end time. The next page starts at the last end time of this one (rows at that
     * instant come again and are upserted by betId); the cursor is that time in epoch ms.
     */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        long from = query.cursor() == null ? query.from().toEpochMilli() : Long.parseLong(query.cursor());
        long to = query.to().toEpochMilli();
        int count = Math.clamp(query.pageSize(), MIN_HISTORY_PAGE, MAX_HISTORY_PAGE);
        Map<String, String> form = credentials(client);
        form.put("count", String.valueOf(count));
        form.put("bet_type", "1");
        form.put("from_time", String.valueOf(from));
        form.put("to_time", String.valueOf(to));
        HistoryPage page = parseHistory(post(client, stripSlash(client.setting("historyUrl")) + "/Bet/v4/GetHistoryForSpecificTimeRange", form),
                client.providerCode());
        if (page.rows() < count) {
            return new BetPullPage(page.records(), null, false);
        }
        long next = page.lastEndTime();
        if (next <= from) {
            // a full page within one millisecond: step over it rather than loop
            log.warn("PG history page of {} rows all ended at {}; continuing after it", page.rows(), from);
            next = from + 1;
        }
        boolean more = next < to;
        return new BetPullPage(page.records(), more ? String.valueOf(next) : null, more);
    }

    /** @param rows rows of the page including skipped ones; {@code lastEndTime} the latest betEndTime (paging) */
    record HistoryPage(List<ProviderBetRecordView> records, int rows, long lastEndTime) {
    }

    /** {@code {"data":[{betId, parentBetId, playerName, gameId, currency, betAmount, winAmount, betTime, betEndTime}],"error":null}}. */
    static HistoryPage parseHistory(String json, String providerCode) {
        JsonNode response = JsonUtils.mapper().readTree(json);
        requireOk(response, "GetHistoryForSpecificTimeRange");
        JsonNode rows = response.path("data");
        List<ProviderBetRecordView> records = new ArrayList<>();
        long lastEndTime = 0;
        for (JsonNode r : rows) {
            long ended = r.path("betEndTime").asLong(0);
            long placed = r.path("betTime").asLong(ended);
            lastEndTime = Math.max(lastEndTime, ended);
            String betId = r.path("betId").asString(null);
            Long userId = PlayerIds.tryDecode(r.path("playerName").asString(null));
            if (userId == null) {
                log.warn("PG bet {} belongs to unknown player {}", betId, r.path("playerName").asString(null));
                continue;
            }
            String parent = r.path("parentBetId").asString("");
            records.add(new ProviderBetRecordView(providerCode, betId, parent.isBlank() ? betId : parent, userId,
                    r.path("currency").asString(null), r.path("gameId").asString(null),
                    r.path("betAmount").asDecimal(BigDecimal.ZERO), r.path("winAmount").asDecimal(BigDecimal.ZERO), "SETTLED",
                    Instant.ofEpochMilli(placed), Instant.ofEpochMilli(ended > 0 ? ended : placed)));
        }
        return new HistoryPage(records, rows.size(), lastEndTime);
    }

    /** Bet and win arrive together; features close through later TransferInOut calls of the same parent bet. */
    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    /** Callback names are matched case-insensitively ({@code Cash/TransferInOut} = {@code cash/transferinout}). */
    private static String action(CallbackRequest request) {
        return request.action().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> credentials(ProviderClient client) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("operator_token", client.config().operatorId());
        form.put("secret_key", client.secret());
        return form;
    }

    /** Every PG API call carries a trace id. */
    private static String post(ProviderClient client, String uri, Map<String, String> form) {
        return client.rest().post()
                .uri(uri + "?trace_id=" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Forms.encode(form))
                .retrieve()
                .body(String.class);
    }

    private static void requireOk(JsonNode response, String call) {
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IllegalStateException("PG " + call + " failed: " + error.path("code").asString("") + " "
                    + error.path("message").asString(""));
        }
    }

    private static String accountCurrency(ProviderClient client) {
        List<String> currencies = client.config().currencies();
        if (currencies.isEmpty()) {
            throw new IllegalStateException("provider " + client.providerCode() + ": currencies must be configured");
        }
        return currencies.getFirst();
    }

    private static long millis(String value, CallbackRequest request) {
        try {
            return Long.parseLong(value.strip());
        } catch (RuntimeException e) {
            return request.receivedAt().toEpochMilli();
        }
    }

    private static BigDecimal balance(BigDecimal value, int scale) {
        String text = Fields.balance(value, scale);
        return text == null ? null : new BigDecimal(text);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static CallbackResponse error(String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message(code));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", null);
        body.put("error", error);
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    private static String message(String code) {
        return switch (code) {
            case "1034" -> "Invalid request";
            case "1200" -> "Internal server error";
            case "3004" -> "Player does not exist";
            case "3021" -> "Bet does not exist";
            case "3073" -> "Bet failed";
            case "3202" -> "Not enough balance";
            default -> "Error";
        };
    }
}
