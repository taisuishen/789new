package com.bingo789.common.mq.event;

import com.bingo789.common.core.line.UserLine;

import java.math.BigDecimal;
import java.time.Instant;

public record WithdrawRequestedEvent(
        String orderNo,
        long userId,
        Integer userLine,
        String currency,
        BigDecimal amount,
        String channelCode,
        String clientIp,
        String deviceId,
        Instant requestedAt) {

    /** A message written before the field existed carries no line: it belongs to line 1. */
    public WithdrawRequestedEvent {
        userLine = UserLine.orDefault(userLine);
    }
}
