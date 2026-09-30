package com.bingo789.common.core;

import com.bingo789.common.core.trace.TraceContext;

/**
 * Envelope for player-facing and back-office APIs.
 * Internal RPC (wallet etc.) returns plain DTOs and signals business outcomes in the DTO itself.
 */
public record Result<T>(int code, String message, T data, String traceId) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(CommonErrorCode.SUCCESS.code(), CommonErrorCode.SUCCESS.message(), data, TraceContext.current());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.code(), errorCode.message(), null, TraceContext.current());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null, TraceContext.current());
    }

    public boolean isSuccess() {
        return code == CommonErrorCode.SUCCESS.code();
    }
}
