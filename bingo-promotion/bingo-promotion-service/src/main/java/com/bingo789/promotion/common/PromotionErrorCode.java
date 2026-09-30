package com.bingo789.promotion.common;

import com.bingo789.common.core.ErrorCode;

/** Promotion error codes (9xxxx). */
public enum PromotionErrorCode implements ErrorCode {

    PROMOTION_NOT_FOUND(90001, "promotion not found", 404),
    INVALID_PROMOTION(90002, "invalid promotion", 400),
    VERSION_CONFLICT(90003, "promotion was changed by someone else, reload it and retry", 409),
    INVALID_STATUS_CHANGE(90004, "status change not allowed", 409);

    private final int code;
    private final String message;
    private final int httpStatus;

    PromotionErrorCode(int code, String message, int httpStatus) {
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
