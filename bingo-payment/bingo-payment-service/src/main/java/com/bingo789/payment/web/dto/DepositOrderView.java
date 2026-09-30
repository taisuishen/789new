package com.bingo789.payment.web.dto;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.payment.domain.DepositOrder;
import com.bingo789.payment.domain.DepositStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record DepositOrderView(
        String orderNo,
        String currency,
        BigDecimal amount,
        String channelCode,
        DepositStatus status,
        Instant createdAt,
        Instant paidAt) {

    public static DepositOrderView of(DepositOrder order) {
        return new DepositOrderView(order.getOrderNo(), order.getCurrency(), order.getAmount(), order.getChannelCode(),
                order.getStatus(), BingoTime.toInstant(order.getCreatedAt()), BingoTime.toInstant(order.getPaidAt()));
    }
}
