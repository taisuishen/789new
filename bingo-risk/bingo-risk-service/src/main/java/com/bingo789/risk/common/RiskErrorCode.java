package com.bingo789.risk.common;

import com.bingo789.common.core.ErrorCode;

public enum RiskErrorCode implements ErrorCode {

    REVIEW_NOT_FOUND(80001, "review task not found", 404),
    REVIEW_ALREADY_DECIDED(80002, "review task already decided", 409);

    private final int code;
    private final String message;
    private final int httpStatus;

    RiskErrorCode(int code, String message, int httpStatus) {
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
