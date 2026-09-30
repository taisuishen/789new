package com.bingo789.game.wallet;

/**
 * External player id given to providers. Keeps the provider-facing id decoupled from our user id format,
 * so it can later become an opaque mapping without touching adapters.
 */
public final class PlayerIds {

    private static final String PREFIX = "b789";

    private PlayerIds() {
    }

    public static String encode(long userId) {
        return PREFIX + userId;
    }

    /** @return the user id, or null when the id was not issued by us */
    public static Long tryDecode(String playerId) {
        if (playerId == null || !playerId.startsWith(PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(playerId.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
