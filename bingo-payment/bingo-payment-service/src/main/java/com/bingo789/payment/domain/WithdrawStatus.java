package com.bingo789.payment.domain;

/**
 * CREATED -> PENDING_AUDIT -> APPROVED -> PAYING -> SUCCEEDED | FAILED, or PENDING_AUDIT -> REJECTED,
 * or CREATED -> FAILED when the wallet refuses the freeze.
 */
public enum WithdrawStatus {
    /** Order written; the WITHDRAW_FREEZE outcome is not known yet. */
    CREATED,
    /**
     * Reserved. The current flow moves a successful freeze straight to PENDING_AUDIT in the same transaction
     * as the outbox message, so this state is never persisted.
     */
    FROZEN,
    /** Funds frozen, waiting for the risk decision. */
    PENDING_AUDIT,
    /** Approved; payout not submitted yet. */
    APPROVED,
    /** Payout submitted (or submission outcome unknown). Only a definite channel result leaves this state. */
    PAYING,
    SUCCEEDED,
    /** Freeze refused, or the channel definitively failed the payout (funds unfrozen). */
    FAILED,
    /** Rejected by risk (funds unfrozen). */
    REJECTED
}
