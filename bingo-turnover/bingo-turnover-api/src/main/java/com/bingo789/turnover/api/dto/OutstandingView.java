package com.bingo789.turnover.api.dto;

import java.math.BigDecimal;

/** @param remaining sum of (required - achieved) over the ACTIVE buckets; zero means the player may withdraw freely */
public record OutstandingView(
        long userId,
        String currency,
        int activeBuckets,
        BigDecimal required,
        BigDecimal achieved,
        BigDecimal remaining) {
}
