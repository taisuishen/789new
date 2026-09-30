package com.bingo789.turnover.service;

import com.bingo789.common.core.Money;
import com.bingo789.common.core.game.GameKeys;
import com.bingo789.common.core.game.GameType;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.RoundSettledEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A settled round as the waterfall needs it (UTC+8 local times, normalised game type).
 *
 * @param betTime     first bet of the round: only buckets created at or before it may take the round
 * @param settledTime a balance-based clear only closes buckets created at or before it
 */
record SettledRound(
        long userId,
        int userLine,
        String currency,
        String roundKey,
        String providerCode,
        String gameCode,
        String gameKey,
        String gameType,
        BigDecimal validBet,
        BigDecimal balanceAfter,
        LocalDateTime betTime,
        LocalDateTime settledTime) {

    private static final String SETTLED = "SETTLED";

    /** Null for rounds that can neither fill a bucket nor trigger a balance-based clear. */
    static SettledRound of(RoundSettledEvent e) {
        if (!SETTLED.equals(e.status()) || e.providerCode() == null || e.roundId() == null || e.currency() == null) {
            return null;
        }
        BigDecimal validBet = e.validBet() == null || e.validBet().signum() < 0 ? Money.ZERO : e.validBet();
        if (validBet.signum() == 0 && e.balanceAfter() == null) {
            return null;
        }
        Instant settled = e.settledTime() != null ? e.settledTime() : Instant.now();
        Instant bet = e.betTime() != null ? e.betTime() : settled;
        String gameCode = e.gameCode() == null ? "" : e.gameCode();
        return new SettledRound(e.userId(), e.userLine(), e.currency(), roundKey(e), e.providerCode(), gameCode,
                GameKeys.of(e.providerCode(), gameCode), GameType.parse(e.gameType()).name(), validBet, e.balanceAfter(),
                LocalDateTime.ofInstant(bet, BingoTime.ZONE), LocalDateTime.ofInstant(settled, BingoTime.ZONE));
    }

    /** Includes the user: in multi-player games (e.g. bingo rooms) many players share one provider round id. */
    static String roundKey(RoundSettledEvent e) {
        return e.providerCode() + ':' + e.roundId() + ':' + e.userId();
    }

    boolean fills() {
        return validBet.signum() > 0;
    }

    LocalDate betDate() {
        return betTime.toLocalDate();
    }
}
