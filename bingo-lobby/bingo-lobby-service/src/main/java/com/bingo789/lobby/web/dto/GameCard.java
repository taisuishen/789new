package com.bingo789.lobby.web.dto;

import java.math.BigDecimal;

/**
 * Player-facing game tile. The id is a string because snowflake ids exceed the 2^53 integer precision of
 * JavaScript clients.
 */
public record GameCard(
        String id,
        String providerCode,
        String gameCode,
        String name,
        String category,
        BigDecimal theoreticalRtp,
        String thumbnailUrl) {
}
