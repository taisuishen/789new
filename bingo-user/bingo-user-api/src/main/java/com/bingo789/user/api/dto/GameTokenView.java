package com.bingo789.user.api.dto;

import java.time.Instant;

/**
 * Launch token handed to the provider in the game URL and presented back in its "authenticate" callback (and, by some
 * providers, on every call). {@code valid=false} with null fields means unknown or expired token.
 *
 * @param playAllowed the player may place stakes right now (not self-excluded, suspended, ...). A valid token of a
 *                    player who may no longer play still identifies them: wins, refunds and corrections of rounds
 *                    already in play must be credited.
 */
public record GameTokenView(
        boolean valid,
        String token,
        Long userId,
        String providerCode,
        String gameCode,
        String currency,
        Instant expiresAt,
        boolean playAllowed) {

    public static GameTokenView invalid() {
        return new GameTokenView(false, null, null, null, null, null, null, false);
    }
}
