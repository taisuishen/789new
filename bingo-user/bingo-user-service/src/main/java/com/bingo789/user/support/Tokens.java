package com.bingo789.user.support;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Opaque bearer tokens for player sessions and game launches: 32 random bytes, base64url without padding. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String BEARER_PREFIX = "Bearer ";

    private Tokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** Cheap shape check before a Redis lookup, so arbitrary client input never becomes a key. */
    public static boolean isWellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    /** Token from an {@code Authorization: Bearer ...} header, or null when absent or malformed. */
    public static String fromBearerHeader(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return isWellFormed(token) ? token : null;
    }
}
