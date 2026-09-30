package com.bingo789.reconcile.web.dto;

import com.bingo789.reconcile.entity.ReconDiff;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Times are UTC+8. */
public record DiffView(
        long id,
        String level,
        String providerCode,
        String currency,
        LocalDateTime periodStart,
        String metric,
        BigDecimal platformValue,
        BigDecimal providerValue,
        BigDecimal diff,
        String status,
        String note,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt) {

    public static DiffView of(ReconDiff d) {
        return new DiffView(d.getId(), d.getLevel().name(), d.getProviderCode(), d.getCurrency(), d.getPeriodStart(),
                d.getMetric(), d.getPlatformValue(), d.getProviderValue(), d.getDiff(), d.getStatus().name(), d.getNote(),
                d.getResolvedAt(), d.getCreatedAt());
    }
}
