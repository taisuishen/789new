package com.bingo789.game.adapter.model;

/** Thrown by adapters and guards to end a callback with a specific {@link CallbackError}. */
public class CallbackException extends RuntimeException {

    private final CallbackError error;

    public CallbackException(CallbackError error, String message) {
        super(message, null, false, false);
        this.error = error;
    }

    public CallbackError error() {
        return error;
    }

    public static CallbackException auth(String message) {
        return new CallbackException(CallbackError.AUTH_FAILED, message);
    }

    public static CallbackException badRequest(String message) {
        return new CallbackException(CallbackError.BAD_REQUEST, message);
    }

    public static CallbackException unknownAction(String action) {
        return new CallbackException(CallbackError.UNKNOWN_ACTION, "unknown action " + action);
    }
}
