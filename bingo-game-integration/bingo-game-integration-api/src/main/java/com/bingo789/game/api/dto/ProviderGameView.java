package com.bingo789.game.api.dto;

import java.math.BigDecimal;

/** A game as listed by the provider's catalogue API, used by the lobby sync job. */
public record ProviderGameView(
        String providerCode,
        String gameCode,
        String name,
        String category,
        BigDecimal theoreticalRtp,
        String thumbnailUrl,
        boolean mobileSupported,
        boolean desktopSupported) {
}
