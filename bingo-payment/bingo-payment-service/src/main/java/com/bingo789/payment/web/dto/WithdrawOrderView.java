package com.bingo789.payment.web.dto;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.payment.domain.WithdrawOrder;
import com.bingo789.payment.domain.WithdrawStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Player-facing view: no payee token, no risk / audit details. */
public record WithdrawOrderView(
        String orderNo,
        String currency,
        BigDecimal amount,
        BigDecimal fee,
        String channelCode,
        WithdrawStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static WithdrawOrderView of(WithdrawOrder order) {
        return new WithdrawOrderView(order.getOrderNo(), order.getCurrency(), order.getAmount(), order.getFee(),
                order.getChannelCode(), order.getStatus(), BingoTime.toInstant(order.getCreatedAt()),
                BingoTime.toInstant(order.getUpdatedAt()));
    }
}
