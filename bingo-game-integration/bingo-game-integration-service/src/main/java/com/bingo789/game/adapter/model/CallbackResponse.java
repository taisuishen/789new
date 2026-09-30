package com.bingo789.game.adapter.model;

import java.nio.charset.StandardCharsets;

/** Provider-formatted response; status, content type and body are entirely up to the adapter. */
public record CallbackResponse(int httpStatus, String contentType, byte[] body) {

    public static CallbackResponse json(int httpStatus, String json) {
        return new CallbackResponse(httpStatus, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    public static CallbackResponse text(int httpStatus, String text) {
        return new CallbackResponse(httpStatus, "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }
}
