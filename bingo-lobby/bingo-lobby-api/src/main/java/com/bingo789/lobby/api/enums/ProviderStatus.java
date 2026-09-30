package com.bingo789.lobby.api.enums;

/**
 * MAINTENANCE is set by operators; AUTO_MAINTENANCE is set by game-integration when the provider's
 * circuit breaker opens and is cleared automatically when it closes again.
 * Operator-set MAINTENANCE/DISABLED is never overridden by automatic transitions.
 */
public enum ProviderStatus {
    ACTIVE,
    MAINTENANCE,
    AUTO_MAINTENANCE,
    DISABLED
}
