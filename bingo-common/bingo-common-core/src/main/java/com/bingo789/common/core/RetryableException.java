package com.bingo789.common.core;

/**
 * The request could not be served right now but may succeed when retried unchanged (e.g. a shard being migrated).
 * Mapped to HTTP 503; callers must treat it as "outcome unknown / not applied" and retry with the same idempotency key.
 */
public class RetryableException extends RuntimeException {

    public RetryableException(String message) {
        super(message);
    }
}
