package com.bingo789.turnover.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One 稽核记录.
 *
 * @param recordType CREATE (amount = required), WAGER (amount = valid bet taken from one round), CLEAR (amount =
 *                   remainder dropped)
 * @param statusAfter bucket status after the change (a fulfilling WAGER shows COMPLETED)
 * @param reason     CREATE: source type; closing WAGER / CLEAR: FULFILLED, REMAINING_BELOW_THRESHOLD,
 *                   BALANCE_BELOW_THRESHOLD or MANUAL
 * @param gameType   WAGER only, like providerCode / gameCode
 */
public record TurnoverRecordView(
        long id,
        long bucketId,
        int userLine,
        String recordType,
        String currency,
        BigDecimal amount,
        BigDecimal achievedAfter,
        BigDecimal remainingAfter,
        String statusAfter,
        String reason,
        String operator,
        String providerCode,
        String gameCode,
        String gameType,
        Instant createdAt) {
}
