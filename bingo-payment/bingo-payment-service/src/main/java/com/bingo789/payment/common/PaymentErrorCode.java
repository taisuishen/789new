package com.bingo789.payment.common;

import com.bingo789.common.core.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {

    DEPOSIT_NOT_ALLOWED(70001, "deposit not allowed", 403),
    WITHDRAW_NOT_ALLOWED(70002, "withdrawal not allowed", 403),
    CHANNEL_UNAVAILABLE(70003, "payment channel unavailable", 400),
    CURRENCY_NOT_SUPPORTED(70004, "currency not supported", 400),
    AMOUNT_OUT_OF_RANGE(70005, "amount out of range", 400),
    DEPOSIT_LIMIT_EXCEEDED(70006, "deposit limit exceeded", 400),
    INSUFFICIENT_FUNDS(70007, "insufficient funds", 400),
    WALLET_LOCKED(70008, "wallet locked", 403),
    WITHDRAW_FAILED(70009, "withdrawal failed", 400),
    ORDER_NOT_FOUND(70010, "order not found", 404),
    ORDER_STATE_INVALID(70011, "order state does not allow this operation", 409),
    AUDIT_CONFLICT(70012, "a different audit decision was already recorded", 409);

    private final int code;
    private final String message;
    private final int httpStatus;

    PaymentErrorCode(int code, String message, int httpStatus) {
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
