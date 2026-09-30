package com.bingo789.promotion.web.dto;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.domain.PromotionEntry;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Player-facing promotion. Only the "display" object of config_json is exposed; the terms (rates, caps, wagering)
 * stay on the server.
 *
 * @param id      string, so JavaScript clients keep all 64 bits
 * @param display config_json.display, or null
 */
public record ActivityView(
        String id,
        String name,
        String type,
        Instant startTime,
        Instant endTime,
        JsonNode display) {

    public static ActivityView of(PromotionEntry entry) {
        return new ActivityView(String.valueOf(entry.id()), entry.name(), entry.type(),
                BingoTime.toInstant(entry.startTime()), BingoTime.toInstant(entry.endTime()), entry.display());
    }
}
