package com.bingo789.common.mq.event;

import com.bingo789.common.core.line.UserLine;

import java.math.BigDecimal;
import java.time.Instant;

public record DepositSucceededEvent(
        String orderNo,
        long userId,
        Integer userLine,
        String currency,
        BigDecimal amount,
        String channelCode,
        boolean firstDeposit,
        Instant succeededAt) {

    /** A message written before the field existed carries no line: it belongs to line 1. */
    public DepositSucceededEvent {
        userLine = UserLine.orDefault(userLine);
    }
}
