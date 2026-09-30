package com.bingo789.common.mq.event;

import com.bingo789.common.core.line.UserLine;

import java.math.BigDecimal;
import java.time.Instant;

/** A bet record as reported by the provider's bet-history API (the provider's side of reconciliation). */
public record ProviderBetEvent(
        String providerCode,
        String providerBetId,
        String roundId,
        long userId,
        Integer userLine,
        String currency,
        String gameCode,
        BigDecimal betAmount,
        BigDecimal payoutAmount,
        String status,
        Instant betTime,
        Instant settleTime) {

    /** A message written before the field existed carries no line: it belongs to line 1. */
    public ProviderBetEvent {
        userLine = UserLine.orDefault(userLine);
    }
}
