package com.bingo789.payment.domain;

/**
 * CREATED -> PENDING -> SUCCEEDED | FAILED | EXPIRED.
 * A payment confirmed after expiry still moves EXPIRED -> SUCCEEDED: money the channel received is always credited.
 */
public enum DepositStatus {
    /** Order row written, channel not called yet (or the call failed). */
    CREATED,
    /** Payment instructions handed to the player; waiting for the channel. */
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED
}
