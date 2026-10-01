package com.bingo789.game.adapter.evo;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evolution "One Wallet" seamless wallet: {@code POST /callback/EVO/{check|sid|balance|debit|credit|cancel|promo_payout|close}
 * ?authToken=...}, JSON body, JSON replies {@code {"status","balance"|"sid","uuid"}}, always HTTP 200; amounts are
 * decimals in currency units.
 * <p>
 * Auth: the query parameter {@code authToken} (static per casino) is compared in constant time. The player is
 * {@code userId} (our player id); {@code sid} is the game token we handed Evolution at launch. check / sid / balance /
 * debit are sessions: the gateway verifies the sid and that it belongs to {@code userId} (INVALID_SID otherwise).
 * credit / cancel / promo_payout / close are accepted with an expired sid, as Evolution requires for settlements.
 * <p>
 * Idempotency: a bet is keyed on {@code transaction.refId}, which its credit and cancel reference; the credit is the
 * bet's one settlement, keyed on the same refId (type PAYOUT, so it does not collide with the bet), which makes a second
 * credit of a bet a replay. The cancel reverses the bet with that refId. Duplicates answer BET_ALREADY_EXIST (debit) or
 * BET_ALREADY_SETTLED (credit, cancel, promo) with the balance, as Evolution specifies; the request {@code uuid} is
 * echoed.
 * <p>
 * Deliberate deviations from the old code: a missing / wrong authToken answers INVALID_TOKEN_ID (old: TEMPORARY_ERROR
 * when missing); the sid is checked against user-service game tokens instead of the Redis "historical sid" registry;
 * {@code sid} cannot mint a new session (no command issues tokens): it returns the presented sid while it is valid,
 * else INVALID_SID; a credit without a live debit (never received, or cancelled) answers BET_DOES_NOT_EXIST (old paid a
 * credit without debit and answered BET_ALREADY_SETTLED after a cancel); a cancel of an unknown debit answers OK and
 * leaves a permanent tombstone (old BET_DOES_NOT_EXIST + 60-minute tombstone: the wallet does not report that the
 * target was missing); a cancel after the credit is refused with BET_ALREADY_SETTLED (as before: the wallet never
 * refunds a paid-out bet); jackpot promo payouts are
 * JACKPOT_PAYOUT; balances are rounded DOWN at {@code balance-scale}. Bet pull parses every {@code data[]} day (old: the
 * first only), game code = table id (old: the round id) and keeps cancelled rounds as CANCELLED. Thousand-unit
 * currencies (ID2 / VN2) are not supported (they are not configured currencies).
 * <p>
 * Configuration ({@code bingo.providers.EVO}): {@code operator-id} = casino id, {@code secret} = callback authToken,
 * {@code base-url} = Evolution API host; secrets {@code ua2Token} (UA2 launch API token), {@code gameHistoryToken}
 * (Game History API, Basic auth with the casino id), {@code lobbyToken} (External Lobby API); settings {@code gameHost}
 * (host prefixed to the launch entry, default: base-url), {@code skin} (brand skin, default {@code 1}). Bet pull: the
 * defaults fit (no paging; one call per window).
 */
@Slf4j
@Component
public class EvoAdapter implements ProviderAdapter {

    static final String NAME = "EVO";

    /** Launch codes that open the slots category of these studios instead of a live table. */
    private static final Set<String> SLOT_STUDIOS = Set.of("FP", "BTG", "NLC", "NETENT", "REDTIGER");

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
        String token = Forms.query(request).get("authToken");
        if (token == null || token.isBlank()) {
            throw CallbackException.auth("missing authToken");
        }
        if (!Hmacs.safeEquals(client.secret(), token)) {
            throw CallbackException.auth("authToken mismatch");
        }
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        JsonNode b = body(request);
        String userId = Fields.required(text(b, "userId"), "userId");
        String sid = text(b, "sid");
        String currency = text(b, "currency");
        return switch (request.action()) {
            case "check", "sid", "balance" -> new WalletCommand.Session(sid, new WalletCommand.GetBalance(userId, currency));
            case "close" -> new WalletCommand.GetBalance(userId, currency);
            case "debit" -> {
                Trade t = trade(b);
                yield new WalletCommand.Session(sid,
                        new WalletCommand.Bet(userId, currency, t.refId(), t.round(), t.table(), t.amount(), false));
            }
            case "credit" -> {
                Trade t = trade(b);
                yield new WalletCommand.Payout(userId, currency, t.refId(), t.round(), t.table(), t.amount(),
                        TxnType.PAYOUT, t.refId(), true);
            }
            case "cancel" -> {
                Trade t = trade(b);
                yield new WalletCommand.Rollback(userId, currency, t.id(), t.refId(), TxnType.BET, t.round(), t.table());
            }
            case "promo_payout" -> promo(b, userId, currency);
            default -> throw CallbackException.unknownAction(request.action());
        };
    }

    /** @param round game.id (the round), or the refId when Evolution sends none */
    private record Trade(String id, String refId, BigDecimal amount, String round, String table) {
    }

    private static Trade trade(JsonNode b) {
        JsonNode t = b.path("transaction");
        String refId = Fields.required(text(t, "refId"), "transaction.refId");
        String round = text(b.path("game"), "id");
        return new Trade(Fields.required(text(t, "id"), "transaction.id"), refId,
                nonNegative(text(t, "amount"), "transaction.amount"), round == null ? refId : round, gameCode(b.path("game")));
    }

    private static WalletCommand promo(JsonNode b, String userId, String currency) {
        JsonNode p = b.path("promoTransaction");
        String id = Fields.required(text(p, "id"), "promoTransaction.id");
        String type = text(p, "type");
        TxnType payoutType = type != null && type.toLowerCase(Locale.ROOT).contains("jackpot")
                ? TxnType.JACKPOT_PAYOUT : TxnType.PROMO_PAYOUT;
        return new WalletCommand.Payout(userId, currency, id, "promo:" + id, gameCode(b.path("game")),
                nonNegative(text(p, "amount"), "promoTransaction.amount"), payoutType, null, true);
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status(command, outcome));
        String action = request.action();
        if (outcome.isSuccess() && (action.equals("check") || action.equals("sid"))) {
            body.put("sid", text(body(request), "sid"));
        } else if (outcome.balance() != null) {
            body.put("balance", new BigDecimal(Fields.balance(outcome.balance(), client.config().balanceScale())));
        }
        body.put("uuid", uuid(request));
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    static String status(WalletCommand command, CommandOutcome outcome) {
        WalletCommand inner = command instanceof WalletCommand.Session s ? s.command() : command;
        return switch (outcome.code()) {
            case SUCCESS -> !outcome.replay() || inner instanceof WalletCommand.GetBalance ? "OK"
                    : inner instanceof WalletCommand.Bet ? "BET_ALREADY_EXIST" : "BET_ALREADY_SETTLED";
            case INSUFFICIENT_FUNDS -> "INSUFFICIENT_FUNDS";
            case INVALID_TOKEN -> "INVALID_SID";
            case PLAYER_LOCKED -> "ACCOUNT_LOCKED";
            case BET_NOT_FOUND, TXN_NOT_FOUND -> "BET_DOES_NOT_EXIST";
            // a debit whose refId was cancelled before it arrived is final; a settlement of a cancelled bet is settled
            case TXN_CANCELLED -> inner instanceof WalletCommand.Bet ? "FINAL_ERROR_ACTION_FAILED" : "BET_ALREADY_SETTLED";
            // cancel of a debit that was already credited: Evolution's own answer
            case BET_SETTLED -> "BET_ALREADY_SETTLED";
            case PLAYER_NOT_FOUND, INVALID_REQUEST -> "INVALID_PARAMETER";
        };
    }

    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        String status = switch (error) {
            case AUTH_FAILED -> "INVALID_TOKEN_ID";
            case BAD_REQUEST, UNKNOWN_ACTION -> "INVALID_PARAMETER";
            case RATE_LIMITED, SYSTEM_RETRYABLE -> "TEMPORARY_ERROR";
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("uuid", uuid(request));
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    // ================================================================ outbound

    /** UA2 user authentication: registers the session (our game token as sid) and returns the entry path. */
    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        if (c.demo()) {
            throw new IllegalArgumentException("EVO seamless launch has no demo mode");
        }
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", c.gameToken());
        if (c.clientIp() != null) {
            session.put("ip", c.clientIp());
        }
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("id", playerId);
        player.put("update", false);
        player.put("language", c.language() == null ? "en" : c.language());
        player.put("currency", c.currency());
        player.put("session", session);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("brand", Map.of("skin", client.setting("skin", "1")));
        config.put("channel", Map.of("wrapped", false, "mobile", "MOBILE".equalsIgnoreCase(c.platform())));
        Object game = launchGame(c.gameCode());
        if (game != null) {
            config.put("game", game);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uuid", UUID.randomUUID().toString());
        body.put("player", player);
        body.put("config", config);
        String response = client.rest().post()
                .uri("/ua/v1/{casinoId}/{token}", client.config().operatorId(), client.secret("ua2Token"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonUtils.toJson(body))
                .retrieve()
                .body(String.class);
        String entry = JsonUtils.mapper().readTree(response).path("entry").asString("");
        if (entry.isEmpty()) {
            throw new IllegalStateException("EVO launch returned no entry");
        }
        return new LaunchView(entry.startsWith("http") ? entry : client.setting("gameHost", client.config().baseUrl()) + entry, null);
    }

    /** Lobby for blank / "EVO", the slots category for a slot studio code, else the live table with that id. */
    private static Object launchGame(String gameCode) {
        if (gameCode == null || gameCode.isBlank() || gameCode.equalsIgnoreCase(NAME)) {
            return null;
        }
        if (SLOT_STUDIOS.contains(gameCode.toUpperCase(Locale.ROOT))) {
            return Map.of("category", "slots");
        }
        return Map.of("table", Map.of("id", gameCode));
    }

    /** External Lobby API "state": the casino's tables keyed by table id. */
    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        String casinoId = client.config().operatorId();
        String response = client.rest().get()
                .uri("/api/lobby/v1/{casinoId}/state", casinoId)
                .headers(h -> h.setBasicAuth(casinoId, client.secret("lobbyToken")))
                .retrieve()
                .body(String.class);
        JsonNode tables = JsonUtils.mapper().readTree(response).path("tables");
        List<ProviderGameView> games = new ArrayList<>();
        if (tables.isArray()) {
            for (JsonNode t : tables) {
                games.add(game(client.providerCode(), text(t, "id"), t));
            }
        } else {
            for (Map.Entry<String, JsonNode> t : tables.properties()) {
                games.add(game(client.providerCode(), t.getKey(), t.getValue()));
            }
        }
        return games;
    }

    private static ProviderGameView game(String providerCode, String tableId, JsonNode t) {
        return new ProviderGameView(providerCode, tableId, t.path("name").asString(tableId), t.path("gameType").asString(null),
                null, t.path("videoSnapshot").path("thumbnails").path("L").asString(null), true, true);
    }

    /** Game History API: every game of the casino in [from, to), one participant = one record. No paging. */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        String casinoId = client.config().operatorId();
        String response = client.rest().get()
                .uri(builder -> builder.path("/api/gamehistory/v1/casino/games")
                        .queryParam("startDate", query.from().truncatedTo(ChronoUnit.MILLIS).toString())
                        .queryParam("endDate", query.to().truncatedTo(ChronoUnit.MILLIS).toString())
                        .build())
                .headers(h -> h.setBasicAuth(casinoId, client.secret("gameHistoryToken")))
                .retrieve()
                .body(String.class);
        return new BetPullPage(parseHistory(response, client.providerCode()), null, false);
    }

    /** {@code data[].games[].participants[]}: bet = sum of stakes, payout = sum of payouts, bet time = first placedOn. */
    static List<ProviderBetRecordView> parseHistory(String json, String providerCode) {
        List<ProviderBetRecordView> records = new ArrayList<>();
        for (JsonNode day : JsonUtils.mapper().readTree(json).path("data")) {
            for (JsonNode game : day.path("games")) {
                String status = switch (String.valueOf(text(game, "status")).toLowerCase(Locale.ROOT)) {
                    case "resolved" -> "SETTLED";
                    case "cancelled" -> "CANCELLED";
                    default -> "OPEN";
                };
                String round = text(game, "id");
                String table = text(game.path("table"), "id");
                for (JsonNode p : game.path("participants")) {
                    Long userId = PlayerIds.tryDecode(text(p, "playerId"));
                    if (userId == null) {
                        log.warn("EVO game {} has unknown player {}", round, text(p, "playerId"));
                        continue;
                    }
                    BigDecimal stake = BigDecimal.ZERO;
                    BigDecimal payout = BigDecimal.ZERO;
                    Instant placed = null;
                    for (JsonNode bet : p.path("bets")) {
                        stake = stake.add(decimalOrZero(bet, "stake"));
                        payout = payout.add(decimalOrZero(bet, "payout"));
                        Instant at = time(text(bet, "placedOn"));
                        placed = placed == null || at != null && at.isBefore(placed) ? at : placed;
                    }
                    records.add(new ProviderBetRecordView(providerCode, text(p, "playerGameId"), round, userId,
                            text(p, "currency"), table, stake, payout, status,
                            placed != null ? placed : time(text(game, "startedAt")),
                            "OPEN".equals(status) ? null : time(text(game, "settledAt"))));
                }
            }
        }
        return records;
    }

    /** No round query is used: open rounds are closed by credit / cancel, or escalated by the resolver. */
    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    private static JsonNode body(CallbackRequest request) {
        if (request.body().length == 0) {
            throw CallbackException.badRequest("empty body");
        }
        try {
            JsonNode node = JsonUtils.mapper().readTree(request.body());
            if (!node.isObject()) {
                throw CallbackException.badRequest("body is not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw CallbackException.badRequest("malformed json");
        }
    }

    /** The request uuid, echoed in every reply; a fresh one when the body is unreadable. */
    private static String uuid(CallbackRequest request) {
        try {
            String uuid = text(JsonUtils.mapper().readTree(request.body()), "uuid");
            return uuid != null ? uuid : UUID.randomUUID().toString();
        } catch (RuntimeException e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String gameCode(JsonNode game) {
        String table = text(game.path("details").path("table"), "id");
        return table != null ? table : text(game, "type");
    }

    private static BigDecimal nonNegative(String value, String field) {
        BigDecimal amount = Fields.amount(value, field);
        if (amount.signum() < 0) {
            throw CallbackException.badRequest(field + " must not be negative");
        }
        return amount;
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

    private static Instant time(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value.strip()).toInstant();
    }
}
