package com.bingo789.promotion.domain;

public enum BonusStatus {
    /** Recorded, not paid yet (or wallet outcome unknown: retried). */
    PENDING,
    PAID,
    /** Refused by the wallet; needs ops attention. */
    FAILED
}
