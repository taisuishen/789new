package com.bingo789.game.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Provider bet record normalized to platform terms (userId decoded from the provider player id). */
public record ProviderBetRecordView(
        String providerCode,
        String providerBetId,
        String roundId,
        long userId,
        String currency,
        String gameCode,
        BigDecimal betAmount,
        BigDecimal payoutAmount,
        String status,
        Instant betTime,
        Instant settleTime) {
}
