package com.bingo789.game.adapter.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * A raw provider callback. The body is kept as the exact bytes received, because signatures are computed
 * over the raw payload.
 *
 * @param headers     lower-cased header names
 * @param queryString raw query string (some providers use GET for balance), may be null
 * @param sourceIp    client address after stripping our own trusted proxies
 */
public record CallbackRequest(
        String providerCode,
        String action,
        Map<String, String> headers,
        String queryString,
        byte[] body,
        String sourceIp,
        Instant receivedAt) {

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
