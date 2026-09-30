package com.bingo789.user.api.dto;

import java.time.Instant;

/**
 * Launch token handed to the provider in the game URL and presented back in its "authenticate" callback.
 * {@code valid=false} with null fields means unknown or expired token.
 */
public record GameTokenView(
        boolean valid,
        String token,
        Long userId,
        String providerCode,
        String gameCode,
        String currency,
        Instant expiresAt) {

    public static GameTokenView invalid() {
        return new GameTokenView(false, null, null, null, null, null, null);
    }
}
