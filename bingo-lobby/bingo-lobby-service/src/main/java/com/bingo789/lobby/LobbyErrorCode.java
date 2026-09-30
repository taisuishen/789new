package com.bingo789.lobby;

import com.bingo789.common.core.ErrorCode;

/** Lobby error codes (5xxxx). */
public enum LobbyErrorCode implements ErrorCode {

    GAME_NOT_FOUND(50001, "game not found", 404),
    GAME_UNAVAILABLE(50002, "game is currently unavailable", 409),
    PROVIDER_UNAVAILABLE(50003, "game provider is under maintenance", 409),
    PLAYER_NOT_ALLOWED(50004, "player is not allowed to play", 403),
    PLATFORM_NOT_SUPPORTED(50005, "game is not available on this platform", 400),
    WALLET_LOCKED(50006, "wallet is locked", 403),
    INVALID_STATUS(50007, "status change not allowed", 400),
    PROVIDER_NOT_FOUND(50008, "game provider not found", 404);

    private final int code;
    private final String message;
    private final int httpStatus;

    LobbyErrorCode(int code, String message, int httpStatus) {
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
