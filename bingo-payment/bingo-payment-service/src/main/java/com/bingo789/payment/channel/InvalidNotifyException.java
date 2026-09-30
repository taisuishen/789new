package com.bingo789.payment.channel;

/** A notify that failed verification (signature, timestamp window, malformed body). Never processed. */
public class InvalidNotifyException extends RuntimeException {

    public InvalidNotifyException(String message) {
        super(message);
    }

    public InvalidNotifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
