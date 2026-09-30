package com.bingo789.common.core;

/**
 * Error code contract. Each service defines its own enum implementing this interface,
 * using its own numeric range (see {@link CommonErrorCode} for the allocation table).
 */
public interface ErrorCode {

    int code();

    String message();

    /** HTTP status to use when this error reaches a player-facing endpoint. */
    default int httpStatus() {
        return 400;
    }
}
