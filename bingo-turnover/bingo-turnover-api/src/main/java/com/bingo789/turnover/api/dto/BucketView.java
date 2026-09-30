package com.bingo789.turnover.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param scopeType   GAME, GAME_TYPE or ALL (TurnoverScope)
 * @param scopeValue  GAME: "PROVIDER:GAME_CODE"; GAME_TYPE: a GameType name; ALL: null
 * @param sourceType  DEPOSIT, BONUS, REBATE or MANUAL
 * @param status      ACTIVE, COMPLETED, CLEARED or VOID
 * @param closeReason FULFILLED, REMAINING_BELOW_THRESHOLD, BALANCE_BELOW_THRESHOLD or MANUAL
 */
public record BucketView(
        long id,
        long userId,
        int userLine,
        String currency,
        String scopeType,
        String scopeValue,
        String sourceType,
        String sourceNo,
        BigDecimal baseAmount,
        BigDecimal multiplier,
        BigDecimal required,
        BigDecimal achieved,
        BigDecimal remaining,
        String status,
        String closeReason,
        Instant createdAt,
        Instant closedAt) {
}
