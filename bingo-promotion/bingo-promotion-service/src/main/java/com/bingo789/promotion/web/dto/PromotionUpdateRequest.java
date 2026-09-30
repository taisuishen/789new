package com.bingo789.promotion.web.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Replaces the editable fields (the type and the status are not editable here). Validated by PromotionAdminService.
 *
 * @param version the version the operator edited; a stale version is refused with a conflict
 */
public record PromotionUpdateRequest(
        String name,
        List<Integer> userLines,
        Instant startTime,
        Instant endTime,
        Integer sort,
        JsonNode config,
        Integer version) {
}
