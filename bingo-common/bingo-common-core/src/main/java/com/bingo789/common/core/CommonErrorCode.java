package com.bingo789.common.core;

/**
 * Platform-wide error codes.
 * <p>
 * Range allocation: 1xxxx common, 2xxxx user, 3xxxx wallet, 4xxxx game-integration, 5xxxx lobby,
 * 6xxxx bet-record, 7xxxx payment, 8xxxx risk, 9xxxx promotion, 10xxxx reconcile.
 */
public enum CommonErrorCode implements ErrorCode {

    SUCCESS(0, "success", 200),
    BAD_REQUEST(10000, "bad request", 400),
    UNAUTHORIZED(10001, "unauthorized", 401),
    FORBIDDEN(10002, "forbidden", 403),
    NOT_FOUND(10003, "not found", 404),
    TOO_MANY_REQUESTS(10004, "too many requests", 429),
    REGION_NOT_ALLOWED(10005, "service not available in your region", 451),
    INVALID_AMOUNT(10006, "invalid amount", 400),
    SERVICE_UNAVAILABLE(10500, "service temporarily unavailable", 503),
    SYSTEM_ERROR(10999, "system error", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    CommonErrorCode(int code, String message, int httpStatus) {
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
