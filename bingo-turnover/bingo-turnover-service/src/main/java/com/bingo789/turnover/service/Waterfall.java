package com.bingo789.turnover.service;

import com.bingo789.turnover.domain.BucketStatus;
import com.bingo789.turnover.domain.CloseReason;
import com.bingo789.turnover.domain.TurnoverBucket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure bucket arithmetic on in-memory copies (the caller persists the result).
 * <p>
 * A round's valid bet is consumed from the player's ACTIVE buckets of the round's currency in this order: GAME
 * buckets of exactly this game, then GAME_TYPE buckets of the game's type, then ALL buckets; within a scope the
 * oldest bucket first. Each unit of valid bet counts once: what one bucket takes, the next one does not see. A
 * bucket only takes rounds whose first bet is not earlier than the bucket itself.
 */
public final class Waterfall {

    /** closed_by / operator of automatic changes. */
    public static final String SYSTEM = "SYSTEM";

    private Waterfall() {
    }

    record Take(TurnoverBucket bucket, BigDecimal amount) {
    }

    /**
     * @param buckets                ACTIVE buckets in consumption order (scope rank, created_at, id); mutated
     * @param completeBelowRemaining a remainder below this completes the bucket (null = off)
     * @return what each bucket took, in waterfall order (list index = seq)
     */
    static List<Take> fill(List<TurnoverBucket> buckets, SettledRound round, BigDecimal completeBelowRemaining) {
        List<Take> takes = new ArrayList<>();
        BigDecimal left = round.validBet();
        for (TurnoverBucket bucket : buckets) {
            if (left.signum() <= 0) {
                break;
            }
            if (!takes(bucket, round)) {
                continue;
            }
            BigDecimal take = left.min(bucket.remaining());
            if (take.signum() <= 0) {
                continue;
            }
            bucket.setAchievedAmount(bucket.getAchievedAmount().add(take));
            BigDecimal remaining = bucket.remaining();
            if (remaining.signum() == 0) {
                close(bucket, BucketStatus.COMPLETED, CloseReason.FULFILLED, round);
            } else if (completeBelowRemaining != null && remaining.compareTo(completeBelowRemaining) < 0) {
                close(bucket, BucketStatus.COMPLETED, CloseReason.REMAINING_BELOW_THRESHOLD, round);
            }
            takes.add(new Take(bucket, take));
            left = left.subtract(take);
        }
        return takes;
    }

    /**
     * The balance the requirements protected is (almost) gone: every ACTIVE bucket of the currency that existed when
     * the round settled is cleared. Buckets created later (a new deposit) are left alone, so a redelivered old round
     * can never clear them.
     */
    static List<TurnoverBucket> clearForLowBalance(List<TurnoverBucket> buckets, SettledRound round) {
        List<TurnoverBucket> cleared = new ArrayList<>();
        for (TurnoverBucket bucket : buckets) {
            if (bucket.getStatus() == BucketStatus.ACTIVE && bucket.getCurrency().equals(round.currency())
                    && !bucket.getCreatedAt().isAfter(round.settledTime())) {
                close(bucket, BucketStatus.CLEARED, CloseReason.BALANCE_BELOW_THRESHOLD, round);
                cleared.add(bucket);
            }
        }
        return cleared;
    }

    static boolean takes(TurnoverBucket bucket, SettledRound round) {
        if (bucket.getStatus() != BucketStatus.ACTIVE || !bucket.getCurrency().equals(round.currency())
                || bucket.getCreatedAt().isAfter(round.betTime())) {
            return false;
        }
        return switch (bucket.getScopeType()) {
            case GAME -> bucket.getScopeValue().equals(round.gameKey());
            case GAME_TYPE -> bucket.getScopeValue().equals(round.gameType());
            case ALL -> true;
        };
    }

    private static void close(TurnoverBucket bucket, BucketStatus status, CloseReason reason, SettledRound round) {
        bucket.setStatus(status);
        bucket.setCloseReason(reason);
        bucket.setClosedBy(SYSTEM);
        bucket.setClosedAt(round.settledTime());
    }
}
