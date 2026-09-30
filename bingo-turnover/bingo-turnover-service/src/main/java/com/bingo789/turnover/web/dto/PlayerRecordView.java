package com.bingo789.turnover.web.dto;

import com.bingo789.turnover.api.dto.TurnoverRecordView;

import java.math.BigDecimal;
import java.time.Instant;

/** A 稽核记录 as the player sees it: no line, no operator. */
public record PlayerRecordView(
        long bucketId,
        String recordType,
        String currency,
        BigDecimal amount,
        BigDecimal remainingAfter,
        String statusAfter,
        String reason,
        String gameCode,
        String gameType,
        Instant createdAt) {

    public static PlayerRecordView of(TurnoverRecordView r) {
        return new PlayerRecordView(r.bucketId(), r.recordType(), r.currency(), r.amount(), r.remainingAfter(),
                r.statusAfter(), r.reason(), r.gameCode(), r.gameType(), r.createdAt());
    }
}
