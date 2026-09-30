package com.bingo789.turnover.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param clearBelowBalance      after a settled round leaves the player's balance below this, every ACTIVE bucket of
 *                               the currency is cleared (the money the requirement protected is gone); null = off
 * @param completeBelowRemaining a bucket whose remaining requirement drops below this counts as fulfilled; null = off
 * @param depositMultiplier      each deposit creates an ALL-games bucket of amount x multiplier; 0 = none
 */
public record TurnoverSettingView(
        int userLine,
        String currency,
        BigDecimal clearBelowBalance,
        BigDecimal completeBelowRemaining,
        BigDecimal depositMultiplier,
        String updatedBy,
        Instant updatedAt) {
}
