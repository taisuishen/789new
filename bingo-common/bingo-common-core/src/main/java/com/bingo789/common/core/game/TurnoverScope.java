package com.bingo789.common.core.game;

/**
 * Which bets count towards a wagering requirement ("稽核桶"). A settled round's valid bet is consumed from the
 * player's buckets from the narrowest scope to the widest: GAME, then GAME_TYPE, then ALL.
 * <ul>
 *   <li>{@link #GAME}: scope value {@link GameKeys#of} ("PROVIDER:GAME_CODE");</li>
 *   <li>{@link #GAME_TYPE}: scope value a {@link GameType} name, e.g. "SLOT";</li>
 *   <li>{@link #ALL}: every game, no scope value.</li>
 * </ul>
 */
public enum TurnoverScope {
    GAME,
    GAME_TYPE,
    ALL;

    /** Validates a scope / value pair from configuration; returns an error text or null when valid. */
    public static String validate(String scope, String value) {
        TurnoverScope parsed;
        try {
            parsed = scope == null ? ALL : valueOf(scope);
        } catch (IllegalArgumentException e) {
            return "unknown turnover scope " + scope;
        }
        return switch (parsed) {
            case ALL -> value == null || value.isBlank() ? null : "scope ALL takes no value";
            case GAME_TYPE -> GameType.isKnown(value) ? null : "unknown game type " + value;
            case GAME -> GameKeys.isValid(value) ? null : "game scope value must be PROVIDER:GAME_CODE";
        };
    }
}
