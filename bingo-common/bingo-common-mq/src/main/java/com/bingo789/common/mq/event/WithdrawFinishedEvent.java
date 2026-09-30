package com.bingo789.common.mq.event;

import com.bingo789.common.core.line.UserLine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload of {@code Topics.WITHDRAW_FINISHED}: status SUCCEEDED, FAILED or REJECTED.
 */
public record WithdrawFinishedEvent(
        String orderNo,
        long userId,
        Integer userLine,
        String currency,
        BigDecimal amount,
        BigDecimal fee,
        String status,
        String reason,
        Instant finishedAt) {

    /** A message written before the field existed carries no line: it belongs to line 1. */
    public WithdrawFinishedEvent {
        userLine = UserLine.orDefault(userLine);
    }
}
