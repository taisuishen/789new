package com.bingo789.risk.rule;

import com.bingo789.common.mq.event.WithdrawRequestedEvent;

import java.math.BigDecimal;
import java.time.Instant;

/** @param userLine the player's line as stamped on the withdrawal order; stored on every row risk writes for it */
public record WithdrawContext(
        String orderNo,
        long userId,
        int userLine,
        String currency,
        BigDecimal amount,
        String channelCode,
        String clientIp,
        String deviceId,
        Instant requestedAt) {

    public static WithdrawContext of(WithdrawRequestedEvent event) {
        return new WithdrawContext(event.orderNo(), event.userId(), event.userLine(), event.currency(), event.amount(),
                event.channelCode(), event.clientIp(), event.deviceId(),
                event.requestedAt() != null ? event.requestedAt() : Instant.now());
    }
}
