package com.bingo789.game.adapter.ps;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PlayStar (PS) seamless wallet: callbacks {@code GET /callback/PS/<auth|getbalance|bet|result|refundbet|bonusaward>}
 * with the parameters in the query string, JSON replies {@code {"status_code":..,"balance":<cents>}}, always HTTP 200.
 * Amounts and balances are integer cents; no callback carries a currency (the game session's currency applies).
 * <p>
 * Auth: PS signs nothing. The only credential is {@code access_token} (our game token, put into the launch URL),
 * present on every call, so every money call is a {@link WalletCommand.Session}; protect the endpoint with the IP
 * allow-list. {@code member_id} is ignored, identity always comes from the token (as before). Our tokens are
 * base64url, so the old "'+' came back as ' '" repair is not needed.
 * <p>
 * Idempotency: {@code txn_id} is the round id and the key of the bet, of its result (same id, the wallet key includes
 * the type) and of the refund (reversal of exactly that bet). {@code bonusaward} is a stakeless JACKPOT_PAYOUT keyed by
 * {@code bonus_id} in the round of its game {@code txn_id}, so the round total matches PS's feed ({@code win + jp}).
 * Duplicates answer 0 with the current balance.
 * <p>
 * Deliberate deviations from the old integration: {@code result} is always credited (the old code refused wins with
 * STOP_BET when the player was over a responsible-gaming limit) and credits {@code total_win} only (the old code also
 * credited the jackpot contribution {@code jp_contrib}, a stake-side amount, in currency units instead of cents); bet
 * lookups have no 2-hour window; a refund of a bet we never received succeeds (tombstone, the late bet is then
 * rejected) instead of answering 2; zero-stake bets are accepted; failures never answer "duplicate" without money
 * having moved.
 * <p>
 * A player who was suspended or self-excluded mid-round can no longer bet (status 4) but still receives the results,
 * refunds and bonus awards of rounds in play (GameTokenView.playAllowed). A token expires 4 hours after its last use;
 * a credit presented after that answers 1 and is found by the daily reconciliation.
 * PS has no round query and no usable game-list feed (the catalogue is maintained manually).
 * <p>
 * Configuration ({@code bingo.providers.PS}): {@code operator-id} = PS {@code host_id} (PS issues one per currency:
 * configure one provider code per currency with exactly one entry in {@code currencies}), {@code base-url} = PS API
 * host (launch and feed). No secret. Bet pull: the history feed is unpaged, keep windows small
 * ({@code bingo.bet-record.pull.providers.PS.max-window: 10m}); other defaults fit.
 */
@Slf4j
@Component
public class PsAdapter implements ProviderAdapter {

    static final String NAME = "PS";

    static final int OK = 0;
    static final int TOKEN_INVALID = 1;
    static final int TXN_INVALID = 2;
    static final int INSUFFICIENT_BALANCE = 3;
    static final int STOP_BET = 4;
    static final int SYSTEM_ERROR = 5;

    private static final DateTimeFormatter FEED_WINDOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter FEED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LOBBY = "PS-LOBBY";

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    /** PS signs nothing: the access token is verified by the gateway (Session), the source by the IP allow-list. */
    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        Map<String, String> p = Forms.query(request);
        String token = p.get("access_token");
        if (token == null || token.isBlank()) {
            throw CallbackException.auth("missing access_token");
        }
        return switch (action(request)) {
            case "auth" -> new WalletCommand.Authenticate(token, null);
            case "getbalance" -> new WalletCommand.Session(token, new WalletCommand.GetBalance(null, null));
            case "bet" -> {
                String txn = txnId(p, "txn_id");
                yield new WalletCommand.Session(token, new WalletCommand.Bet(null, null, txn, txn,
                        Fields.required(p, "game_id"), cents(p, "total_bet"), false));
            }
            // total_win includes the free-game win; jp_contrib is the stake's jackpot contribution, not a win
            case "result" -> {
                String txn = txnId(p, "txn_id");
                yield new WalletCommand.Session(token, new WalletCommand.Payout(null, null, txn, txn,
                        Fields.required(p, "game_id"), cents(p, "total_win"), TxnType.PAYOUT, txn, true));
            }
            // no amount: the refund reverses exactly the stored bet of txn_id
            case "refundbet" -> {
                String txn = txnId(p, "txn_id");
                yield new WalletCommand.Session(token, new WalletCommand.Rollback(null, null, "refund:" + txn, txn,
                        TxnType.BET, txn, p.get("game_id")));
            }
            case "bonusaward" -> {
                String bonusId = txnId(p, "bonus_id");
                String round = p.get("txn_id") == null || p.get("txn_id").isBlank() ? "bonus:" + bonusId : txnId(p, "txn_id");
                yield new WalletCommand.Session(token, new WalletCommand.Payout(null, null, bonusId, round,
                        p.get("game_id"), cents(p, "bonus_reward"), TxnType.JACKPOT_PAYOUT, null, true));
            }
            default -> throw CallbackException.unknownAction(request.action());
        };
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        int status = switch (outcome.code()) {
            // duplicates (replay) are a normal success with the current balance
            case SUCCESS -> OK;
            case INVALID_TOKEN, PLAYER_NOT_FOUND -> TOKEN_INVALID;
            case INSUFFICIENT_FUNDS -> INSUFFICIENT_BALANCE;
            case PLAYER_LOCKED -> STOP_BET;
            // result / refund of an unknown bet, or a bet after its refund: "transaction invalid"
            case BET_NOT_FOUND, TXN_NOT_FOUND, TXN_CANCELLED, BET_SETTLED -> TXN_INVALID;
            case INVALID_REQUEST -> SYSTEM_ERROR;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status_code", status);
        if ("auth".equals(action(request))) {
            String member = status == OK ? outcome.playerId() : null;
            body.put("member_id", member);
            body.put("member_name", member);
        }
        body.put("balance", Fields.toCents(outcome.balance()));
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    /** PS has no distinct "retry" code: 5 SYSTEM_ERROR, which PS retries (never 0). */
    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        int status = error == CallbackError.AUTH_FAILED ? TOKEN_INVALID : SYSTEM_ERROR;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status_code", status);
        if ("auth".equals(action(request))) {
            body.put("member_id", null);
            body.put("member_name", null);
        }
        body.put("balance", 0);
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    // ================================================================ outbound

    /** No server call: the launch URL carries host id, game, language and our game token. */
    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("host_id", client.config().operatorId());
        params.put("game_id", c.gameCode() == null || c.gameCode().isBlank() ? LOBBY : c.gameCode());
        params.put("lang", language(c.language()));
        params.put("access_token", c.gameToken());
        if (c.lobbyUrl() != null && c.lobbyUrl().startsWith("http")) {
            params.put("return_url", c.lobbyUrl());
        }
        return new LaunchView(client.config().baseUrl() + "/launch/?" + Forms.encode(params), null);
    }

    /** PS's game-list feed is not used; the lobby catalogue is maintained manually. */
    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        return List.of();
    }

    /** Finished games of [from, to) in one unpaged call (times in UTC+8, as PS expects). */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("host_id", client.config().operatorId());
        params.put("start_dtm", FEED_WINDOW.format(query.from().atOffset(BingoTime.ZONE)));
        params.put("end_dtm", FEED_WINDOW.format(query.to().atOffset(BingoTime.ZONE)));
        // detail_type=0 adds the jackpot details
        params.put("detail_type", "0");
        String json = client.rest().get()
                .uri(URI.create(client.config().baseUrl() + "/feed/gamehistory/?" + Forms.encode(params)))
                .retrieve()
                .body(String.class);
        String currency = client.config().currencies().isEmpty() ? null : client.config().currencies().getFirst();
        return new BetPullPage(parseFeed(json, client.providerCode(), currency), null, false);
    }

    /**
     * {@code {"<yyyy-MM-dd>": {"<member_id>": [record, ...]}}}; the outer key is the end date, {@code tm} the end time.
     * The payout is the round's win plus its jackpot win ({@code win + jp}, cents), which PS pays through /result and
     * /bonusaward of the same txn id ({@code sn}).
     */
    static List<ProviderBetRecordView> parseFeed(String json, String providerCode, String currency) {
        List<ProviderBetRecordView> records = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return records;
        }
        JsonNode root = JsonUtils.mapper().readTree(json);
        JsonNode status = root.path("status_code");
        if (!status.isMissingNode() && status.asInt() != 0) {
            throw new IllegalStateException("PS gamehistory failed: " + status.asInt() + " " + root.path("message").asString(""));
        }
        for (Map.Entry<String, JsonNode> day : root.properties()) {
            if (!day.getValue().isObject()) {
                continue;
            }
            for (Map.Entry<String, JsonNode> member : day.getValue().properties()) {
                Long userId = PlayerIds.tryDecode(member.getKey());
                if (userId == null) {
                    log.warn("PS records of unknown player {} skipped", member.getKey());
                    continue;
                }
                for (JsonNode r : member.getValue()) {
                    String sn = r.path("sn").asString();
                    BigDecimal bet = cents(r.path("bet"));
                    BigDecimal payout = cents(r.path("win")).add(cents(r.path("jp")));
                    Instant started = LocalDateTime.parse(r.path("s_tm").asString(), FEED_TIME).toInstant(BingoTime.ZONE);
                    Instant ended = LocalDateTime.parse(day.getKey() + " " + r.path("tm").asString(), FEED_TIME)
                            .toInstant(BingoTime.ZONE);
                    records.add(new ProviderBetRecordView(providerCode, sn, sn, userId, currency, r.path("gid").asString(null),
                            bet, payout, "SETTLED", started, ended));
                }
            }
        }
        return records;
    }

    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    private static String action(CallbackRequest request) {
        String action = request.action();
        return action.endsWith("/") ? action.substring(0, action.length() - 1) : action;
    }

    /** PS ids are positive integers; normalized so "007" and "7" are the same transaction. */
    private static String txnId(Map<String, String> p, String field) {
        long id = Fields.longValue(p.get(field), field);
        if (id < 1) {
            throw CallbackException.badRequest(field + " must be positive");
        }
        return String.valueOf(id);
    }

    private static BigDecimal cents(Map<String, String> p, String field) {
        long cents = Fields.longValue(p.get(field), field);
        if (cents < 0) {
            throw CallbackException.badRequest(field + " must not be negative");
        }
        return Fields.fromCents(cents);
    }

    private static BigDecimal cents(JsonNode node) {
        return node.asDecimal().movePointLeft(2);
    }

    private static String language(String language) {
        if (language == null) {
            return "en-US";
        }
        String lang = language.replace('_', '-').toLowerCase(Locale.ROOT);
        if (lang.equals("zh-tw") || lang.equals("zh-hk") || lang.equals("zh-hant")) {
            return "zh-TW";
        }
        return switch (lang.length() > 2 ? lang.substring(0, 2) : lang) {
            case "zh" -> "zh-CN";
            case "th" -> "th-TH";
            case "ko" -> "ko-KR";
            case "vi" -> "vi-VN";
            case "id" -> "id-ID";
            case "ja" -> "ja-JP";
            default -> "en-US";
        };
    }
}
