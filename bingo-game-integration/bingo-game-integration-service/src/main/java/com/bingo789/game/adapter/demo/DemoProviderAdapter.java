package com.bingo789.game.adapter.demo;

import com.bingo789.common.core.crypto.Hmacs;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.ProviderAdapter;
import com.bingo789.game.adapter.TransferCapable;
import com.bingo789.game.adapter.demo.DemoProtocol.AdjustRequest;
import com.bingo789.game.adapter.demo.DemoProtocol.AuthenticateRequest;
import com.bingo789.game.adapter.demo.DemoProtocol.BalanceRequest;
import com.bingo789.game.adapter.demo.DemoProtocol.BetHistoryResponse;
import com.bingo789.game.adapter.demo.DemoProtocol.BetRequest;
import com.bingo789.game.adapter.demo.DemoProtocol.BetWinRequest;
import com.bingo789.game.adapter.demo.DemoProtocol.CallbackReply;
import com.bingo789.game.adapter.demo.DemoProtocol.GamesResponse;
import com.bingo789.game.adapter.demo.DemoProtocol.LaunchRequest;
import com.bingo789.game.adapter.demo.DemoProtocol.LaunchResponse;
import com.bingo789.game.adapter.demo.DemoProtocol.ProviderBalanceResponse;
import com.bingo789.game.adapter.demo.DemoProtocol.RollbackRequest;
import com.bingo789.game.adapter.demo.DemoProtocol.RoundResponse;
import com.bingo789.game.adapter.demo.DemoProtocol.TransferRequest;
import com.bingo789.game.adapter.demo.DemoProtocol.TransferResponse;
import com.bingo789.game.adapter.demo.DemoProtocol.WinRequest;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.RoundStatus;
import com.bingo789.game.adapter.model.Transfer;
import com.bingo789.game.adapter.model.WalletCommand;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reference adapter for the DEMO protocol (see {@link DemoProtocol}); copy it when onboarding a real provider. */
@Slf4j
@Component
public class DemoProviderAdapter implements ProviderAdapter, TransferCapable {

    static final String NAME = "DEMO";

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
        String timestamp = request.header(DemoProtocol.HEADER_TIMESTAMP);
        String signature = request.header(DemoProtocol.HEADER_SIGNATURE);
        if (timestamp == null || signature == null) {
            throw CallbackException.auth("missing signature headers");
        }
        String expected = sign(client.secret(), timestamp, request.action(), request.bodyAsString());
        if (!Hmacs.safeEquals(expected, signature.toLowerCase(Locale.ROOT))) {
            throw CallbackException.auth("signature mismatch");
        }
    }

    @Override
    public Instant requestTimestamp(CallbackRequest request) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(request.header(DemoProtocol.HEADER_TIMESTAMP)));
        } catch (NumberFormatException e) {
            throw CallbackException.auth("invalid timestamp");
        }
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        try {
            return switch (request.action()) {
                case "authenticate" -> {
                    AuthenticateRequest r = read(request, AuthenticateRequest.class);
                    yield new WalletCommand.Authenticate(required(r.token(), "token"), null);
                }
                case "balance" -> {
                    BalanceRequest r = read(request, BalanceRequest.class);
                    yield new WalletCommand.GetBalance(required(r.playerId(), "playerId"), required(r.currency(), "currency"));
                }
                case "bet" -> {
                    BetRequest r = read(request, BetRequest.class);
                    yield new WalletCommand.Bet(required(r.playerId(), "playerId"), required(r.currency(), "currency"),
                            required(r.transactionId(), "transactionId"), required(r.roundId(), "roundId"), r.gameCode(),
                            requiredAmount(r.amount()), r.roundEnded());
                }
                case "win" -> {
                    WinRequest r = read(request, WinRequest.class);
                    yield new WalletCommand.Payout(required(r.playerId(), "playerId"), required(r.currency(), "currency"),
                            required(r.transactionId(), "transactionId"), required(r.roundId(), "roundId"), r.gameCode(),
                            requiredAmount(r.amount()), payoutType(r.winType()), r.betTransactionId(), r.roundEnded());
                }
                case "betwin" -> {
                    BetWinRequest r = read(request, BetWinRequest.class);
                    yield new WalletCommand.BetAndPayout(required(r.playerId(), "playerId"), required(r.currency(), "currency"),
                            required(r.betTransactionId(), "betTransactionId"), required(r.winTransactionId(), "winTransactionId"),
                            required(r.roundId(), "roundId"), r.gameCode(), requiredAmount(r.betAmount()),
                            requiredAmount(r.winAmount()), r.roundEnded());
                }
                case "rollback" -> {
                    RollbackRequest r = read(request, RollbackRequest.class);
                    if (r.refTransactionId() == null && r.roundId() == null) {
                        throw CallbackException.badRequest("refTransactionId or roundId is required");
                    }
                    yield new WalletCommand.Rollback(required(r.playerId(), "playerId"), required(r.currency(), "currency"),
                            required(r.transactionId(), "transactionId"), r.refTransactionId(), TxnType.BET, r.roundId(), r.gameCode());
                }
                case "adjust" -> {
                    AdjustRequest r = read(request, AdjustRequest.class);
                    yield new WalletCommand.Adjust(required(r.playerId(), "playerId"), required(r.currency(), "currency"),
                            required(r.transactionId(), "transactionId"), r.refTransactionId(), r.roundId(),
                            requiredAmount(r.amount()), r.reason());
                }
                default -> throw CallbackException.unknownAction(request.action());
            };
        } catch (JacksonException e) {
            throw CallbackException.badRequest("malformed json: " + e.getMessage());
        }
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        String status = switch (outcome.code()) {
            case SUCCESS -> "OK";
            case INSUFFICIENT_FUNDS -> "INSUFFICIENT_FUNDS";
            case INVALID_TOKEN -> "INVALID_TOKEN";
            case PLAYER_NOT_FOUND -> "PLAYER_NOT_FOUND";
            case PLAYER_LOCKED -> "PLAYER_BLOCKED";
            // DEMO retries the win later, by which time the bet may have arrived
            case BET_NOT_FOUND -> "BET_NOT_FOUND";
            case TXN_CANCELLED, BET_SETTLED -> "TRANSACTION_CANCELLED";
            // DEMO spec: "nothing to roll back" is a success, otherwise the provider retries forever
            case TXN_NOT_FOUND -> command instanceof WalletCommand.Rollback ? "OK" : "TRANSACTION_NOT_FOUND";
            case INVALID_REQUEST -> "INVALID_REQUEST";
        };
        CallbackReply reply = new CallbackReply(status, outcome.playerId(), outcome.currency(),
                format(outcome.balance(), client.config().balanceScale()),
                outcome.platformTxnId() == null ? null : String.valueOf(outcome.platformTxnId()));
        return CallbackResponse.json(200, JsonUtils.toJson(reply));
    }

    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        return switch (error) {
            case AUTH_FAILED -> CallbackResponse.json(401, "{\"status\":\"INVALID_SIGNATURE\"}");
            case BAD_REQUEST, UNKNOWN_ACTION -> CallbackResponse.json(400, "{\"status\":\"INVALID_REQUEST\"}");
            case RATE_LIMITED -> CallbackResponse.json(429, "{\"status\":\"RETRY\"}");
            case SYSTEM_RETRYABLE -> CallbackResponse.json(500, "{\"status\":\"RETRY\"}");
        };
    }

    // ================================================================ outbound

    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        LaunchRequest body = new LaunchRequest(client.config().operatorId(), playerId, c.gameToken(), c.gameCode(),
                c.currency(), c.language(), c.platform(), c.lobbyUrl(), c.demo());
        return new LaunchView(post(client, "/api/v1/launch", body, LaunchResponse.class).url(), null);
    }

    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        GamesResponse response = get(client, "/api/v1/games", Map.of(), GamesResponse.class);
        return response.games().stream()
                .map(g -> new ProviderGameView(client.providerCode(), g.gameCode(), g.name(), g.category(), g.rtp(),
                        g.thumbnail(), g.mobile(), g.desktop()))
                .toList();
    }

    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", query.from().toEpochMilli());
        params.put("to", query.to().toEpochMilli());
        params.put("size", query.pageSize() > 0 ? query.pageSize() : 500);
        if (query.cursor() != null) {
            params.put("cursor", query.cursor());
        }
        BetHistoryResponse response = get(client, "/api/v1/bet-history", params, BetHistoryResponse.class);
        List<ProviderBetRecordView> records = new ArrayList<>(response.items().size());
        for (DemoProtocol.BetHistoryItem item : response.items()) {
            Long userId = PlayerIds.tryDecode(item.playerId());
            if (userId == null) {
                log.warn("DEMO bet {} belongs to unknown player {}", item.betId(), item.playerId());
                continue;
            }
            records.add(new ProviderBetRecordView(client.providerCode(), item.betId(), item.roundId(), userId,
                    item.currency(), item.gameCode(), item.betAmount(), item.winAmount(), item.status(),
                    item.betTime(), item.settleTime()));
        }
        return new BetPullPage(records, response.nextCursor(), response.hasMore());
    }

    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        RoundResponse response = get(client, "/api/v1/rounds/{roundId}", Map.of("playerId", playerId), RoundResponse.class, roundId);
        return switch (response.status()) {
            case "IN_PROGRESS" -> new RoundStatus(RoundStatus.State.IN_PROGRESS, List.of());
            case "CANCELLED" -> new RoundStatus(RoundStatus.State.CANCELLED, List.of());
            case "COMPLETED" -> new RoundStatus(RoundStatus.State.COMPLETED, response.wins() == null ? List.of()
                    : response.wins().stream()
                    .map(w -> new RoundStatus.Settlement(w.transactionId(), w.amount(), payoutType(w.winType())))
                    .toList());
            default -> RoundStatus.unknown();
        };
    }

    // ---------------------------------------------------------------- transfer wallet

    @Override
    public Transfer.Result transferIn(Transfer.Request request, ProviderClient client) {
        return transfer(client, "/api/v1/transfers/in", request);
    }

    @Override
    public Transfer.Result transferOut(Transfer.Request request, ProviderClient client) {
        return transfer(client, "/api/v1/transfers/out", request);
    }

    @Override
    public Transfer.Result queryTransfer(String orderNo, ProviderClient client) {
        return toResult(get(client, "/api/v1/transfers/{orderNo}", Map.of(), TransferResponse.class, orderNo));
    }

    @Override
    public BigDecimal providerBalance(String playerId, String currency, ProviderClient client) {
        return get(client, "/api/v1/players/{playerId}/balance", Map.of("currency", currency),
                ProviderBalanceResponse.class, playerId).balance();
    }

    private Transfer.Result transfer(ProviderClient client, String path, Transfer.Request request) {
        TransferRequest body = new TransferRequest(client.config().operatorId(), request.orderNo(), request.playerId(),
                request.currency(), request.amount());
        return toResult(post(client, path, body, TransferResponse.class));
    }

    private static Transfer.Result toResult(TransferResponse response) {
        Transfer.State state = switch (response.status() == null ? "" : response.status()) {
            case "SUCCEEDED" -> Transfer.State.SUCCEEDED;
            case "FAILED" -> Transfer.State.FAILED;
            case "NOT_FOUND" -> Transfer.State.NOT_FOUND;
            default -> Transfer.State.UNKNOWN;
        };
        return new Transfer.Result(state, response.providerRef(), response.message());
    }

    // ================================================================ helpers

    private static <T> T post(ProviderClient client, String path, Object payload, Class<T> type) {
        String json = JsonUtils.toJson(payload);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String response = client.rest().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(DemoProtocol.HEADER_TIMESTAMP, timestamp)
                .header(DemoProtocol.HEADER_SIGNATURE, sign(client.secret(), timestamp, path, json))
                .body(json)
                .retrieve()
                .body(String.class);
        return JsonUtils.fromJson(response, type);
    }

    /** @param pathTemplate e.g. {@code /api/v1/rounds/{id}}; variables are URI-encoded by the builder, the template is what gets signed */
    private static <T> T get(ProviderClient client, String pathTemplate, Map<String, ?> query, Class<T> type, Object... pathVariables) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String response = client.rest().get()
                .uri(builder -> {
                    builder.path(pathTemplate);
                    for (Map.Entry<String, ?> param : query.entrySet()) {
                        builder.queryParam(param.getKey(), param.getValue());
                    }
                    return builder.build(pathVariables);
                })
                .header(DemoProtocol.HEADER_TIMESTAMP, timestamp)
                .header(DemoProtocol.HEADER_SIGNATURE, sign(client.secret(), timestamp, pathTemplate, ""))
                .retrieve()
                .body(String.class);
        return JsonUtils.fromJson(response, type);
    }

    static String sign(String secret, String timestamp, String scope, String body) {
        return Hmacs.hmacSha256Hex(secret, timestamp + "\n" + scope + "\n" + body);
    }

    private static <T> T read(CallbackRequest request, Class<T> type) {
        if (request.body().length == 0) {
            throw CallbackException.badRequest("empty body");
        }
        return JsonUtils.fromJson(request.body(), type);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw CallbackException.badRequest(field + " is required");
        }
        return value;
    }

    private static BigDecimal requiredAmount(BigDecimal amount) {
        if (amount == null) {
            throw CallbackException.badRequest("amount is required");
        }
        return amount;
    }

    private static TxnType payoutType(String winType) {
        if (winType == null) {
            return TxnType.PAYOUT;
        }
        return switch (winType) {
            case "NORMAL" -> TxnType.PAYOUT;
            case "FREESPIN" -> TxnType.FREE_PAYOUT;
            case "JACKPOT" -> TxnType.JACKPOT_PAYOUT;
            case "PROMO" -> TxnType.PROMO_PAYOUT;
            default -> throw CallbackException.badRequest("unknown winType " + winType);
        };
    }

    /** Rounded DOWN: a provider must never believe the player has more than they do. */
    private static String format(BigDecimal balance, int scale) {
        return balance == null ? null : balance.setScale(scale, RoundingMode.DOWN).toPlainString();
    }
}
