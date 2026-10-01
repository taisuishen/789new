package com.bingo789.common.mq.event;

import com.bingo789.common.core.line.UserLine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A game round reached a terminal state in bet-record.
 *
 * @param gameType     {@link com.bingo789.common.core.game.GameType} name from the lobby catalogue (OTHER if unknown)
 * @param gameName     catalogue name at the time the round was recorded (the game code if unknown)
 * @param status       SETTLED or CANCELLED
 * @param validBet     turnover that counts for rebates and wagering requirements (0 when cancelled)
 * @param balanceAfter the player's balance right after the wallet transaction that closed the round; null when
 *                     the round was closed without one (resolver / timeout)
 * @param revision     1 when the round closed, +1 for every later change (late rollback, payout, adjustment). Each
 *                     event is the round's full current state: consumers replace what they applied for an earlier
 *                     revision and ignore revisions they have already seen.
 */
public record RoundSettledEvent(
        String providerCode,
        String roundId,
        long userId,
        Integer userLine,
        String currency,
        String gameCode,
        String gameType,
        String gameName,
        BigDecimal betAmount,
        BigDecimal payoutAmount,
        BigDecimal validBet,
        BigDecimal balanceAfter,
        String status,
        Instant betTime,
        Instant settledTime,
        Integer revision) {

    /** A message written before a field existed: line 1, revision 1. */
    public RoundSettledEvent {
        userLine = UserLine.orDefault(userLine);
        revision = revision == null || revision < 1 ? 1 : revision;
    }

    /** A correction of a round that was already published. */
    public boolean isRevision() {
        return revision > 1;
    }
}
