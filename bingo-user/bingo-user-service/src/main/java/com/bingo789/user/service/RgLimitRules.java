package com.bingo789.user.service;

import com.bingo789.user.entity.UserRgSetting;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Deposit-limit rules. A null limit means "no limit". Tightening applies immediately; loosening (a higher
 * limit or removing the limit) waits for the cool-down in the pending_* columns.
 */
final class RgLimitRules {

    /** Stored in a pending_* column to mean "remove the limit once the cool-down has passed". */
    static final BigDecimal REMOVE_LIMIT = new BigDecimal("-1.0000");

    /** Effective limits plus the increases that are still waiting. */
    record Limits(BigDecimal daily, BigDecimal weekly, BigDecimal monthly,
                  BigDecimal pendingDaily, BigDecimal pendingWeekly, BigDecimal pendingMonthly,
                  Instant pendingEffectiveAt) {
    }

    /**
     * @param current     limit to store as effective now
     * @param pending     value to store in the pending column (null = nothing pending)
     * @param newIncrease true when this request queued a new increase, which (re)starts the cool-down
     */
    record Decision(BigDecimal current, BigDecimal pending, boolean newIncrease) {
    }

    private RgLimitRules() {
    }

    static Limits effective(UserRgSetting s, Instant now) {
        Instant pendingAt = s.getPendingEffectiveAt();
        boolean matured = pendingAt != null && !pendingAt.isAfter(now);
        if (!matured) {
            return new Limits(s.getDailyDepositLimit(), s.getWeeklyDepositLimit(), s.getMonthlyDepositLimit(),
                    s.getPendingDailyDepositLimit(), s.getPendingWeeklyDepositLimit(), s.getPendingMonthlyDepositLimit(),
                    pendingAt);
        }
        return new Limits(
                apply(s.getDailyDepositLimit(), s.getPendingDailyDepositLimit()),
                apply(s.getWeeklyDepositLimit(), s.getPendingWeeklyDepositLimit()),
                apply(s.getMonthlyDepositLimit(), s.getPendingMonthlyDepositLimit()),
                null, null, null, null);
    }

    /**
     * @param current   effective limit
     * @param pending   pending increase for the same period, if any
     * @param requested limit the player asked for
     */
    static Decision decide(BigDecimal current, BigDecimal pending, BigDecimal requested) {
        if (sameLimit(current, requested)) {
            return new Decision(current, null, false);
        }
        if (isTighter(requested, current)) {
            return new Decision(requested, null, false);
        }
        BigDecimal queued = requested == null ? REMOVE_LIMIT : requested;
        boolean alreadyQueued = pending != null && pending.compareTo(queued) == 0;
        return new Decision(current, queued, !alreadyQueued);
    }

    static boolean isRemoval(BigDecimal pending) {
        return pending != null && pending.signum() < 0;
    }

    private static BigDecimal apply(BigDecimal current, BigDecimal pending) {
        if (pending == null) {
            return current;
        }
        return isRemoval(pending) ? null : pending;
    }

    /** Any limit is tighter than no limit. */
    private static boolean isTighter(BigDecimal requested, BigDecimal current) {
        return requested != null && (current == null || requested.compareTo(current) < 0);
    }

    private static boolean sameLimit(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
