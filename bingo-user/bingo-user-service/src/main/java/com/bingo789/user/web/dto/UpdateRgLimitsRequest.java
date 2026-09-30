package com.bingo789.user.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Desired limits (null = no limit). The request is the full desired state: tightening applies immediately,
 * loosening (including removing a limit) is queued for the cool-down. Sending the current effective value for a
 * period cancels its pending increase; to keep a pending increase, send the pending value again.
 */
public record UpdateRgLimitsRequest(
        @PositiveOrZero BigDecimal dailyDepositLimit,
        @PositiveOrZero BigDecimal weeklyDepositLimit,
        @PositiveOrZero BigDecimal monthlyDepositLimit,
        @Min(5) @Max(1440) Integer sessionLimitMinutes) {
}
