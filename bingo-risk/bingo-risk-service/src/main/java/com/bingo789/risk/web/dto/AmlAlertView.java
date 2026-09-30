package com.bingo789.risk.web.dto;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.risk.domain.AmlAlert;
import com.bingo789.risk.domain.AmlAlertStatus;
import com.bingo789.risk.domain.AmlAlertType;

import java.math.BigDecimal;
import java.time.Instant;

/** @param detail raw JSON as stored */
public record AmlAlertView(
        long id,
        long userId,
        AmlAlertType alertType,
        String refNo,
        BigDecimal amount,
        String currency,
        String detail,
        AmlAlertStatus status,
        Instant createdAt) {

    public static AmlAlertView of(AmlAlert alert) {
        return new AmlAlertView(alert.getId(), alert.getUserId(), alert.getAlertType(), alert.getRefNo(), alert.getAmount(),
                alert.getCurrency(), alert.getDetail(), alert.getStatus(), BingoTime.toInstant(alert.getCreatedAt()));
    }
}
