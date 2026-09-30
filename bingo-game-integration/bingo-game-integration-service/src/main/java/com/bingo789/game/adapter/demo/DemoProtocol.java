package com.bingo789.game.adapter.demo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Wire format of the reference "DEMO" provider. It is deliberately close to common seamless-wallet specs,
 * so it serves as the template when writing a real adapter.
 * <ul>
 *   <li>Callbacks: POST /callback/DEMO/{authenticate|balance|bet|win|betwin|rollback|adjust}, JSON body.</li>
 *   <li>Signature: {@code X-Demo-Signature = hex(HMAC-SHA256(secret, timestamp + "\n" + action + "\n" + rawBody))},
 *   {@code X-Demo-Timestamp} = epoch millis. Outbound calls sign the endpoint path template
 *   (e.g. {@code /api/v1/rounds/{roundId}}) instead of {@code action}.</li>
 *   <li>Business outcomes: HTTP 200 with a status field. Duplicates are answered OK with the current balance.
 *   Retryable failures: HTTP 5xx / 429 with status RETRY.</li>
 * </ul>
 */
final class DemoProtocol {

    static final String HEADER_TIMESTAMP = "X-Demo-Timestamp";
    static final String HEADER_SIGNATURE = "X-Demo-Signature";

    private DemoProtocol() {
    }

    // ---------------------------------------------------------------- callbacks (provider -> us)

    record AuthenticateRequest(String token) {
    }

    record BalanceRequest(String playerId, String currency) {
    }

    record BetRequest(String playerId, String currency, String transactionId, String roundId, String gameCode,
                      BigDecimal amount, boolean roundEnded) {
    }

    /** @param winType NORMAL, FREESPIN, JACKPOT or PROMO */
    record WinRequest(String playerId, String currency, String transactionId, String betTransactionId, String roundId,
                      String gameCode, BigDecimal amount, String winType, boolean roundEnded) {
    }

    record BetWinRequest(String playerId, String currency, String betTransactionId, String winTransactionId,
                         String roundId, String gameCode, BigDecimal betAmount, BigDecimal winAmount, boolean roundEnded) {
    }

    /** @param refTransactionId bet to cancel; null cancels the whole round */
    record RollbackRequest(String playerId, String currency, String transactionId, String refTransactionId,
                           String roundId, String gameCode) {
    }

    record AdjustRequest(String playerId, String currency, String transactionId, String refTransactionId,
                         String roundId, BigDecimal amount, String reason) {
    }

    record CallbackReply(String status, String playerId, String currency, String balance, String transactionId) {
    }

    // ---------------------------------------------------------------- outbound (us -> provider)

    record LaunchRequest(String operatorId, String playerId, String token, String gameCode, String currency,
                         String language, String platform, String lobbyUrl, boolean demo) {
    }

    record LaunchResponse(String url) {
    }

    record GameItem(String gameCode, String name, String category, BigDecimal rtp, String thumbnail,
                    boolean mobile, boolean desktop) {
    }

    record GamesResponse(List<GameItem> games) {
    }

    record BetHistoryItem(String betId, String roundId, String playerId, String currency, String gameCode,
                          BigDecimal betAmount, BigDecimal winAmount, String status, Instant betTime, Instant settleTime) {
    }

    record BetHistoryResponse(List<BetHistoryItem> items, String nextCursor, boolean hasMore) {
    }

    /** @param status IN_PROGRESS, COMPLETED, CANCELLED or NOT_FOUND */
    record RoundResponse(String status, List<RoundWin> wins) {
    }

    record RoundWin(String transactionId, BigDecimal amount, String winType) {
    }

    record TransferRequest(String operatorId, String orderNo, String playerId, String currency, BigDecimal amount) {
    }

    /** @param status SUCCEEDED, FAILED or NOT_FOUND */
    record TransferResponse(String status, String providerRef, String message) {
    }

    record ProviderBalanceResponse(BigDecimal balance) {
    }
}
