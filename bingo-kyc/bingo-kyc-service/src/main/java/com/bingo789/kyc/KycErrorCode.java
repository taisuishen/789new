package com.bingo789.kyc;

import com.bingo789.common.core.ErrorCode;

/** 12xxxx: KYC. */
public enum KycErrorCode implements ErrorCode {

    ALREADY_VERIFIED(120001, "identity is already verified", 409),
    SUBMISSION_IN_PROGRESS(120002, "a verification is already in progress", 409),
    DAILY_LIMIT_REACHED(120003, "too many verification attempts today, please try again tomorrow", 429),
    INVALID_IMAGE(120004, "image not found, please upload it again", 400),
    NO_VERIFIED_SELFIE(120005, "no approved identity verification", 409),
    NOT_ALLOWED(120006, "identity verification is not available for this account", 403);

    private final int code;
    private final String message;
    private final int httpStatus;

    KycErrorCode(int code, String message, int httpStatus) {
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
