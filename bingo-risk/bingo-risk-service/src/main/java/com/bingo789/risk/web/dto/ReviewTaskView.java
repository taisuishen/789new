package com.bingo789.risk.web.dto;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.risk.domain.ReviewStatus;
import com.bingo789.risk.domain.RiskReviewTask;

import java.math.BigDecimal;
import java.time.Instant;

public record ReviewTaskView(
        long id,
        String orderNo,
        long userId,
        String currency,
        BigDecimal amount,
        String reasons,
        ReviewStatus status,
        String operator,
        String decisionReason,
        Instant createdAt,
        Instant decidedAt) {

    public static ReviewTaskView of(RiskReviewTask task) {
        return new ReviewTaskView(task.getId(), task.getOrderNo(), task.getUserId(), task.getCurrency(), task.getAmount(),
                task.getReasons(), task.getStatus(), task.getOperator(), task.getDecisionReason(),
                BingoTime.toInstant(task.getCreatedAt()), BingoTime.toInstant(task.getDecidedAt()));
    }
}
