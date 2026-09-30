package com.bingo789.promotion.domain;

public enum RebateStatus {
    /** Computed, not paid yet (or wallet outcome unknown: retried by the next run). */
    PENDING,
    PAID,
    /** Refused by the wallet; needs ops attention. */
    FAILED
}
