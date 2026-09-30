package com.bingo789.promotion.web.dto;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.PromotionEntry;
import com.bingo789.promotion.domain.PromotionStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** Back-office view of a promotion, including its server-side terms. */
public record PromotionAdminView(
        String id,
        String name,
        String type,
        List<Integer> userLines,
        Instant startTime,
        Instant endTime,
        PromotionStatus status,
        int sort,
        JsonNode config,
        int version,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static PromotionAdminView of(Promotion row) {
        return new PromotionAdminView(String.valueOf(row.getId()), row.getName(), row.getPromoType(),
                PromotionEntry.parseLines(row.getUserLines()), BingoTime.toInstant(row.getStartTime()),
                BingoTime.toInstant(row.getEndTime()), row.getStatus(), row.getSort() == null ? 0 : row.getSort(),
                row.getConfigJson() == null ? null : JsonUtils.mapper().readTree(row.getConfigJson()),
                row.getVersion() == null ? 0 : row.getVersion(), row.getCreatedBy(), row.getUpdatedBy(),
                BingoTime.toInstant(row.getCreatedAt()), BingoTime.toInstant(row.getUpdatedAt()));
    }
}
