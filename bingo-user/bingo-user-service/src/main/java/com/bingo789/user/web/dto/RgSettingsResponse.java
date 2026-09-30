package com.bingo789.user.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Player view of the RG settings. Limits are the effective values (null = no limit); increases still waiting
 * for the cool-down are listed separately. Restriction timestamps are null once they have ended.
 */
public record RgSettingsResponse(
        BigDecimal dailyDepositLimit,
        BigDecimal weeklyDepositLimit,
        BigDecimal monthlyDepositLimit,
        Integer sessionLimitMinutes,
        List<PendingIncrease> pendingIncreases,
        Instant selfExcludedUntil,
        Instant coolOffUntil) {

    /**
     * @param period   DAILY, WEEKLY or MONTHLY
     * @param newLimit null when the pending change removes the limit
     */
    public record PendingIncrease(String period, BigDecimal newLimit, Instant effectiveAt) {
    }

    public static RgSettingsResponse none() {
        return new RgSettingsResponse(null, null, null, null, List.of(), null, null);
    }
}
