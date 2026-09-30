package com.bingo789.user.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Responsible-gaming limits set by the player (null = no limit).
 * Payment enforces deposit limits against its own deposit totals.
 */
public record RgLimitsView(
        long userId,
        BigDecimal dailyDepositLimit,
        BigDecimal weeklyDepositLimit,
        BigDecimal monthlyDepositLimit,
        Integer sessionLimitMinutes,
        Instant selfExcludedUntil,
        Instant coolOffUntil) {
}
