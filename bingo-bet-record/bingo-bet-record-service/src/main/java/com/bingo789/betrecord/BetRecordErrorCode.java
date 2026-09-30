package com.bingo789.betrecord;

import com.bingo789.common.core.ErrorCode;

/** Bet-record error codes (6xxxx). */
public enum BetRecordErrorCode implements ErrorCode {

    DATE_RANGE_TOO_LARGE(60001, "date range must not exceed 31 days", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    BetRecordErrorCode(int code, String message, int httpStatus) {
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
