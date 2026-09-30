package com.bingo789.turnover;

import com.bingo789.common.core.ErrorCode;

/** 11xxxx: wagering requirements. */
public enum TurnoverErrorCode implements ErrorCode {

    BUCKET_NOT_FOUND(110001, "wagering requirement not found", 404),
    INVALID_SCOPE(110002, "invalid wagering scope", 400),
    CONCURRENT_UPDATE(110003, "wagering requirement changed concurrently, please retry", 409);

    private final int code;
    private final String message;
    private final int httpStatus;

    TurnoverErrorCode(int code, String message, int httpStatus) {
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
