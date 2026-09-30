package com.bingo789.promotion.service;

/** Result of one wallet credit attempt. */
public enum PayOutcome {
    PAID,
    /** Business rejection by the wallet; the row is marked FAILED. */
    FAILED,
    /** Exception or timeout: the row stays PENDING and is retried with the same bizNo. */
    UNKNOWN
}
