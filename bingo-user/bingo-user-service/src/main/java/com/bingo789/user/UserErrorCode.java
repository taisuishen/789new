package com.bingo789.user;

import com.bingo789.common.core.ErrorCode;

/** User-service error codes (range 2xxxx, see CommonErrorCode for the allocation). */
public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND(20001, "user not found", 404),
    USERNAME_TAKEN(20002, "username is already taken", 409),
    EMAIL_TAKEN(20003, "email is already registered", 409),
    PHONE_TAKEN(20004, "phone number is already registered", 409),
    ACCOUNT_ALREADY_EXISTS(20005, "account already exists", 409),
    UNDERAGE(20006, "minimum age requirement not met", 403),
    COUNTRY_NOT_ALLOWED(20007, "registration is not available in this country", 403),
    CURRENCY_NOT_SUPPORTED(20008, "currency not supported", 400),
    WEAK_PASSWORD(20009, "password does not meet the password policy", 400),
    AGENT_NOT_FOUND(20010, "referring agent not found", 400),

    INVALID_CREDENTIALS(20100, "invalid username or password", 401),
    LOGIN_TEMPORARILY_LOCKED(20101, "too many failed login attempts, try again later", 429),
    ACCOUNT_SUSPENDED(20102, "account is suspended", 403),
    ACCOUNT_CLOSED(20103, "account is closed", 403),

    PLAY_NOT_ALLOWED(20200, "play is not allowed for this account", 403),

    RG_INVALID_PERIOD(20300, "invalid responsible gaming period", 400),
    RG_CONCURRENT_UPDATE(20301, "settings were changed concurrently, please retry", 409),

    SAME_USER_LINE(20400, "player is already on this line", 409),
    LINE_MIGRATION_CONFLICT(20401, "player's line was changed concurrently, please retry", 409),
    INVALID_LINE_SCOPE(20402, "invalid line scope", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    UserErrorCode(int code, String message, int httpStatus) {
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
