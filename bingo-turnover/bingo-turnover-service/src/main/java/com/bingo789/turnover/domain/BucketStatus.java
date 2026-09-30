package com.bingo789.turnover.domain;

public enum BucketStatus {
    ACTIVE,
    /** Required turnover reached (or the remainder fell below the setting's threshold). */
    COMPLETED,
    /** Dropped without being fulfilled: balance below the threshold, or by an operator. */
    CLEARED,
    /** Cancelled because its source was reversed (e.g. a charged-back deposit). */
    VOID
}
