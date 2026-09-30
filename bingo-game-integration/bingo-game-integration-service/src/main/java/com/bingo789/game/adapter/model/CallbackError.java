package com.bingo789.game.adapter.model;

public enum CallbackError {
    /** Signature, source IP or timestamp check failed. */
    AUTH_FAILED,
    /** Payload could not be parsed or failed validation. */
    BAD_REQUEST,
    UNKNOWN_ACTION,
    /** Our inbound rate limit tripped; must be rendered as retryable. */
    RATE_LIMITED,
    /**
     * Outcome unknown (wallet timeout, DB failover ...). Must be rendered as the provider's
     * "system error, retry" code — NEVER as success. The provider retries or rolls back, and
     * reconciliation covers the rest.
     */
    SYSTEM_RETRYABLE
}
