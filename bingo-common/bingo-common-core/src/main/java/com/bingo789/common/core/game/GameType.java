package com.bingo789.common.core.game;

import java.util.Locale;

/**
 * Canonical game type, set per game in the lobby catalogue (game.game_type) and copied onto bet records. Used by
 * wagering-requirement scopes, bonuses and reports. Messages carry the name as a String, so a consumer that does
 * not know a newer type still reads the message ({@link #parse} maps it to {@link #OTHER}).
 */
public enum GameType {
    SLOT,
    FISHING,
    POKER,
    LIVE,
    TABLE,
    ARCADE,
    BINGO,
    LOTTERY,
    SPORTS,
    ESPORTS,
    OTHER;

    public static GameType parse(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }

    /** Strict variant for configuration input (unknown names are errors, not OTHER). */
    public static boolean isKnown(String value) {
        if (value == null) {
            return false;
        }
        for (GameType type : values()) {
            if (type.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
