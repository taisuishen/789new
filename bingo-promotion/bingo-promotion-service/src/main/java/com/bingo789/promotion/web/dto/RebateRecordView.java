package com.bingo789.promotion.web.dto;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.domain.RebateRecord;
import com.bingo789.promotion.domain.RebateStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record RebateRecordView(
        LocalDate statDate,
        String currency,
        BigDecimal validBet,
        BigDecimal amount,
        RebateStatus status,
        Instant paidAt) {

    public static RebateRecordView of(RebateRecord record) {
        return new RebateRecordView(record.getStatDate(), record.getCurrency(), record.getValidBet(), record.getAmount(),
                record.getStatus(), BingoTime.toInstant(record.getPaidAt()));
    }
}
