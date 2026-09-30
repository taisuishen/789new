package com.bingo789.reconcile;

import com.bingo789.common.core.ErrorCode;

/** Reconcile error codes (10xxxx). */
public enum ReconcileErrorCode implements ErrorCode {

    DIFF_NOT_FOUND(100001, "reconciliation diff not found", 404),
    DIFF_ALREADY_CLOSED(100002, "reconciliation diff is already closed", 409),
    DATE_RANGE_TOO_LARGE(100003, "date range too large", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    ReconcileErrorCode(int code, String message, int httpStatus) {
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
