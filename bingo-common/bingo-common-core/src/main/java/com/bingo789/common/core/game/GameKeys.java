package com.bingo789.common.core.game;

/** A game is identified platform-wide by provider code + the provider's game code. */
public final class GameKeys {

    private GameKeys() {
    }

    /** "PROVIDER:GAME_CODE", e.g. "PG:fortune-tiger"; the scope value of a game-level wagering requirement. */
    public static String of(String providerCode, String gameCode) {
        return providerCode + ":" + gameCode;
    }

    public static boolean isValid(String key) {
        int sep = key == null ? -1 : key.indexOf(':');
        return sep > 0 && sep < key.length() - 1;
    }
}
