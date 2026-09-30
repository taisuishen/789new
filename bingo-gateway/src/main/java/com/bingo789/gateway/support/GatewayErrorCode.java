package com.bingo789.gateway.support;

import com.bingo789.common.core.ErrorCode;

/** Errors raised by the gateway itself. Uses 102xx inside the common 1xxxx range (the gateway has no range). */
public enum GatewayErrorCode implements ErrorCode {

    /** Waiting room: the platform is at capacity; the body carries a queue ticket. */
    ADMISSION_QUEUED(10201, "the platform is at capacity, you are in the queue", 429),
    INVALID_QUEUE_TICKET(10202, "invalid or expired queue ticket", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    GatewayErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
