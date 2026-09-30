package com.bingo789.betrecord.web.dto;

import com.bingo789.betrecord.entity.GameRound;
import com.bingo789.common.core.time.BingoTime;

import java.math.BigDecimal;
import java.time.Instant;

/** Player-facing round history row. */
public record RoundView(
        String providerCode,
        String roundId,
        String gameCode,
        String currency,
        BigDecimal betAmount,
        BigDecimal payoutAmount,
        String status,
        Instant startedAt,
        Instant settledAt) {

    public static RoundView of(GameRound r) {
        return new RoundView(r.getProviderCode(), r.getRoundId(), r.getGameCode(), r.getCurrency(),
                r.getBetAmount(), r.getPayoutAmount(), r.getStatus().name(),
                r.getFirstEventAt() == null ? null : r.getFirstEventAt().toInstant(BingoTime.ZONE),
                r.getSettledAt() == null ? null : r.getSettledAt().toInstant(BingoTime.ZONE));
    }
}
