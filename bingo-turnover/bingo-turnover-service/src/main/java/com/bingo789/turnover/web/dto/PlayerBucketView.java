package com.bingo789.turnover.web.dto;

import com.bingo789.turnover.api.dto.BucketView;

import java.math.BigDecimal;
import java.time.Instant;

/** What a player sees of a requirement: no internal ids of the source, no line. */
public record PlayerBucketView(
        long id,
        String currency,
        String scopeType,
        String scopeValue,
        String sourceType,
        BigDecimal required,
        BigDecimal achieved,
        BigDecimal remaining,
        Instant createdAt) {

    public static PlayerBucketView of(BucketView b) {
        return new PlayerBucketView(b.id(), b.currency(), b.scopeType(), b.scopeValue(), b.sourceType(), b.required(),
                b.achieved(), b.remaining(), b.createdAt());
    }
}
