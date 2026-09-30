package com.bingo789.lobby.api.dto;

import java.math.BigDecimal;

public record GameView(
        long id,
        String providerCode,
        String gameCode,
        String name,
        String category,
        String gameType,
        BigDecimal theoreticalRtp,
        String thumbnailUrl,
        boolean available) {
}
