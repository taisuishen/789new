package com.bingo789.promotion.web.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * A new promotion always starts as DRAFT. Validated by PromotionAdminService.
 *
 * @param type      FIRST_DEPOSIT or REBATE; decides the schema of {@code config}
 * @param userLines lines whose players see the promotion
 * @param endTime   exclusive
 * @param sort      higher first; default 0
 * @param config    JSON object: the type's terms plus an optional player-facing "display" object
 */
public record PromotionCreateRequest(
        String name,
        String type,
        List<Integer> userLines,
        Instant startTime,
        Instant endTime,
        Integer sort,
        JsonNode config) {
}
