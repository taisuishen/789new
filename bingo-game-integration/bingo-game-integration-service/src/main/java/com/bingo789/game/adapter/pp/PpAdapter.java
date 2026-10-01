package com.bingo789.game.adapter.pp;

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
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Pragmatic Play seamless wallet ("Casino integration API", callbacks {@code POST /callback/PP/<method>.html},
 * application/x-www-form-urlencoded, JSON replies, always HTTP 200).
 * <p>
 * Signature: {@code hash = md5(join(sorted(params without hash, non-empty), "&") + secretKey)}, over the RAW form
 * parameters as received (the old code hashed its own DTO fields, so any parameter PP added broke the hash).
 * <p>
 * Idempotency: PP's {@code reference} is the unique id of every money call (the old code keyed on gameId+roundId,
 * which swallowed a second bet of the same round). The round is {@code gameId:roundId}. {@code result} closes the
 * round (PP sends one result per round; endRound only confirms it). FRB spin wins arrive through {@code result};
 * {@code bonusWin} is the informational FRB total and moves no money.
 * <p>
 * Configuration ({@code bingo.providers.PP}): {@code operator-id} = secureLogin, {@code secret} = secret key (also the
 * DataFeeds password), {@code base-url} = casino API host; settings {@code feedDomains} (comma-separated DataFeeds API
 * domains of the environments, e.g. {@code api-sg14.ppgames.net}), optional {@code cashierUrl}.
 */
@Slf4j
@Component
public class PpAdapter implements ProviderAdapter {

    static final String NAME = "PP";

    private static final DateTimeFormatter FEED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Slot rounds (no dataType) and live casino rounds, pulled per environment domain. */
    private static final List<String> FEED_TYPES = List.of("", "LC");

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
        Map<String, String> params = Forms.body(request);
        String hash = params.remove("hash");
        if (hash == null || hash.isBlank()) {
            throw CallbackException.auth("missing hash");
        }
        if (!Hmacs.safeEquals(hash(params, client.secret()), hash.toLowerCase())) {
            throw CallbackException.auth("hash mismatch");
        }
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        Map<String, String> p = Forms.body(request);
        return switch (request.action()) {
            case "authenticate.html" -> new WalletCommand.Authenticate(Fields.required(p, "token"), null);
            case "balance.html", "endRound.html", "GetBalancePerGame.html" ->
                    new WalletCommand.GetBalance(Fields.required(p, "userId"), null);
            case "bet.html" -> new WalletCommand.Bet(Fields.required(p, "userId"), null, Fields.required(p, "reference"),
                    round(p), p.get("gameId"), Fields.amount(p.get("amount"), "amount"), false);
            case "result.html" -> result(p);
            case "jackpotWin.html" -> new WalletCommand.Payout(Fields.required(p, "userId"), null,
                    Fields.required(p, "reference"), round(p), p.get("gameId"), Fields.amount(p.get("amount"), "amount"),
                    TxnType.JACKPOT_PAYOUT, null, true);
            case "promoWin.html" -> new WalletCommand.Payout(Fields.required(p, "userId"), null,
                    Fields.required(p, "reference"), "promo:" + Fields.required(p, "campaignId"), p.get("gameId"),
                    Fields.amount(p.get("amount"), "amount"), TxnType.PROMO_PAYOUT, null, true);
            // the refund carries the reference of the bet it cancels; our rollback id is derived from it
            case "refund.html" -> new WalletCommand.Rollback(Fields.required(p, "userId"), null,
                    "refund:" + Fields.required(p, "reference"), p.get("reference"), TxnType.BET,
                    p.get("roundId") == null ? null : round(p), p.get("gameId"));
            // live casino re-settlement; negative amounts debit the player
            case "adjustment.html" -> new WalletCommand.Adjust(Fields.required(p, "userId"), null,
                    Fields.required(p, "reference"), null, round(p), Fields.amount(p.get("amount"), "amount"), "adjustment");
            case "bonusWin.html", "roundDetails.html" -> new WalletCommand.Ack(Fields.required(p, "userId"), null);
            default -> throw CallbackException.unknownAction(request.action());
        };
    }

    /** The round's win, plus a promotional win PP may attach to the same result (its own reference). */
    private static WalletCommand result(Map<String, String> p) {
        String userId = Fields.required(p, "userId");
        WalletCommand.Payout win = new WalletCommand.Payout(userId, null, Fields.required(p, "reference"), round(p),
                p.get("gameId"), Fields.amount(p.get("amount"), "amount"), TxnType.PAYOUT, null, true);
        String promoAmount = p.get("promoWinAmount");
        if (promoAmount == null || promoAmount.isBlank() || new BigDecimal(promoAmount).signum() == 0) {
            return win;
        }
        WalletCommand.Payout promo = new WalletCommand.Payout(userId, null, Fields.required(p, "promoWinReference"),
                round(p), p.get("gameId"), Fields.amount(promoAmount, "promoWinAmount"), TxnType.PROMO_PAYOUT, null, true);
        return new WalletCommand.Batch(userId, null, List.of(win, promo));
    }

    private static String round(Map<String, String> p) {
        return Fields.required(p, "gameId") + ":" + Fields.required(p, "roundId");
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        String action = request.action();
        if (action.equals("refund.html") && outcome.code() == CommandOutcome.Code.TXN_NOT_FOUND) {
            // PP: refunding a bet we never received succeeds without action
            return reply(0, "Success", Map.of("transactionId", ""));
        }
        int error = switch (outcome.code()) {
            case SUCCESS -> 0;
            case INSUFFICIENT_FUNDS -> 1;
            case PLAYER_NOT_FOUND -> 2;
            case INVALID_TOKEN -> 4;
            case PLAYER_LOCKED -> 6;
            // result / jackpot / adjustment before its bet: "internal error, retry", the bet may still arrive
            case BET_NOT_FOUND, TXN_NOT_FOUND -> 100;
            // a bet after its refund, or a win of a cancelled bet: final, PP must not retry
            case TXN_CANCELLED, BET_SETTLED -> 120;
            case INVALID_REQUEST -> 7;
        };
        if (error != 0) {
            return reply(error, description(error), Map.of());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        String cash = Fields.balance(outcome.balance(), client.config().balanceScale());
        switch (action) {
            case "authenticate.html" -> {
                body.put("userId", outcome.playerId());
                body.put("currency", outcome.currency());
                body.put("cash", number(cash));
                body.put("bonus", BigDecimal.ZERO);
            }
            case "GetBalancePerGame.html" -> {
                List<Map<String, Object>> games = new ArrayList<>();
                for (String gameId : Forms.body(request).getOrDefault("gameIdList", "").split(",")) {
                    if (!gameId.isBlank()) {
                        games.add(Map.of("gameId", gameId.strip(), "cash", number(cash), "bonus", BigDecimal.ZERO));
                    }
                }
                body.put("gamesBalances", games);
            }
            case "roundDetails.html", "bonusWin.html" -> {
                // no balance: nothing was read or moved
            }
            case "refund.html" -> body.put("transactionId", txnId(outcome));
            default -> {
                if (!action.equals("balance.html") && !action.equals("endRound.html")) {
                    body.put("transactionId", txnId(outcome));
                }
                body.put("currency", outcome.currency());
                body.put("cash", number(cash));
                body.put("bonus", BigDecimal.ZERO);
                if (action.equals("bet.html")) {
                    body.put("usedPromo", BigDecimal.ZERO);
                }
            }
        }
        return reply(0, "Success", body);
    }

    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        return switch (error) {
            case AUTH_FAILED -> reply(5, description(5), Map.of());
            case BAD_REQUEST, UNKNOWN_ACTION -> reply(7, description(7), Map.of());
            // endRound has its own "retry" code
            case RATE_LIMITED, SYSTEM_RETRYABLE -> "endRound.html".equals(request.action())
                    ? reply(130, "Internal server error on endRound processing", Map.of())
                    : reply(100, description(100), Map.of());
        };
    }

    // ================================================================ outbound

    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        Map<String, String> params = new TreeMap<>();
        params.put("secureLogin", client.config().operatorId());
        params.put("symbol", c.gameCode());
        params.put("language", c.language() == null ? "en" : c.language());
        params.put("token", c.gameToken());
        params.put("externalPlayerId", playerId);
        params.put("platform", "MOBILE".equalsIgnoreCase(c.platform()) ? "MOBILE" : "WEB");
        if (c.lobbyUrl() != null) {
            params.put("lobbyUrl", c.lobbyUrl());
        }
        String cashier = client.setting("cashierUrl", null);
        if (cashier != null) {
            params.put("cashierUrl", cashier);
        }
        if (c.demo()) {
            params.put("playMode", "DEMO");
        }
        JsonNode response = post(client, "/IntegrationService/v3/http/CasinoGameAPI/game/url/", params);
        requireOk(response, "game/url");
        return new LaunchView(response.path("gameURL").asString(), null);
    }

    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        Map<String, String> params = new TreeMap<>();
        params.put("secureLogin", client.config().operatorId());
        JsonNode response = post(client, "/IntegrationService/v3/http/CasinoGameAPI/getCasinoGames/", params);
        requireOk(response, "getCasinoGames");
        List<ProviderGameView> games = new ArrayList<>();
        for (JsonNode g : response.path("gameList")) {
            String platforms = g.path("platform").asString("");
            games.add(new ProviderGameView(client.providerCode(), g.path("gameID").asString(), g.path("gameName").asString(),
                    g.path("typeDescription").asString(null), null, null,
                    platforms.isEmpty() || platforms.contains("MOBILE"), platforms.isEmpty() || platforms.contains("WEB")));
        }
        return games;
    }

    /**
     * DataFeeds "finished game rounds": rounds that ended since {@code timepoint}. One call per environment domain and
     * feed type (slots, live casino); the cursor is the index of the next (domain, type) pair. Rounds that ended at or
     * after {@code to} are left for the next window (the overlap re-reads them; storage is keyed by playSessionID).
     */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        List<String> domains = Arrays.stream(client.setting("feedDomains").split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        int index = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
        String domain = domains.get(index / FEED_TYPES.size());
        String dataType = FEED_TYPES.get(index % FEED_TYPES.size());

        Map<String, String> params = new TreeMap<>();
        if (!dataType.isEmpty()) {
            params.put("dataType", dataType);
        }
        params.put("login", client.config().operatorId());
        params.put("password", client.secret());
        params.put("timepoint", String.valueOf(query.from().toEpochMilli()));
        String csv = client.rest().get()
                .uri("https://" + domain + "/IntegrationService/v3/DataFeeds/gamerounds/finished/?" + Forms.encode(params))
                .retrieve()
                .body(String.class);
        List<ProviderBetRecordView> records = parseFeed(csv, query, client.providerCode());
        int next = index + 1;
        boolean more = next < domains.size() * FEED_TYPES.size();
        return new BetPullPage(records, more ? String.valueOf(next) : null, more);
    }

    /** Line 0 "timepoint=...", line 1 header, then one round per line (UTC times). */
    static List<ProviderBetRecordView> parseFeed(String csv, BetPullQuery query, String providerCode) {
        List<ProviderBetRecordView> records = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return records;
        }
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) {
            return records;
        }
        List<String> header = Arrays.stream(lines[1].split(",")).map(String::strip).toList();
        for (int i = 2; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = lines[i].split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < cells.length; c++) {
                row.put(header.get(c), cells[c].strip());
            }
            Long userId = PlayerIds.tryDecode(row.get("extPlayerID"));
            Instant ended = feedTime(row.get("endDate"));
            if (userId == null || ended == null || !ended.isBefore(query.to())) {
                if (userId == null) {
                    log.warn("PP round {} belongs to unknown player {}", row.get("playSessionID"), row.get("extPlayerID"));
                }
                continue;
            }
            String session = row.get("playSessionID");
            records.add(new ProviderBetRecordView(providerCode, session, session, userId, row.get("currency"),
                    row.get("gameID"), new BigDecimal(row.get("bet")), new BigDecimal(row.get("win")), "SETTLED",
                    feedTime(row.get("startDate")), ended));
        }
        return records;
    }

    /** No round query in the PP seamless API: open rounds are closed by result / refund, or escalated by the resolver. */
    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    static String hash(Map<String, String> params, String secret) {
        String base = new TreeMap<>(params).entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return Ciphers.md5Hex(base + secret);
    }

    private static JsonNode post(ProviderClient client, String path, Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.put("hash", hash(params, client.secret()));
        String response = client.rest().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Forms.encode(signed))
                .retrieve()
                .body(String.class);
        return JsonUtils.mapper().readTree(response);
    }

    private static void requireOk(JsonNode response, String call) {
        if (!"0".equals(response.path("error").asString())) {
            throw new IllegalStateException("PP " + call + " failed: " + response.path("error").asString() + " "
                    + response.path("description").asString());
        }
    }

    private static Instant feedTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value, FEED_TIME).toInstant(ZoneOffset.UTC);
    }

    private static CallbackResponse reply(int error, String description, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.put("error", error);
        body.put("description", description);
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    private static String txnId(CommandOutcome outcome) {
        return outcome.platformTxnId() == null ? "" : String.valueOf(outcome.platformTxnId());
    }

    private static BigDecimal number(String cash) {
        return cash == null ? BigDecimal.ZERO : new BigDecimal(cash);
    }

    private static String description(int error) {
        return switch (error) {
            case 1 -> "Insufficient balance";
            case 2 -> "Player not found";
            case 4 -> "Player authentication failed";
            case 5 -> "Invalid hash code";
            case 6 -> "Player is frozen";
            case 7 -> "Bad parameters in the request, please check post parameters";
            case 100 -> "Internal server error, please retry";
            case 120 -> "Internal server error";
            default -> "Error";
        };
    }
}
