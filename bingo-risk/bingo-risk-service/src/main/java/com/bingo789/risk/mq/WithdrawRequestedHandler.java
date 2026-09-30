package com.bingo789.risk.mq;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.mq.BingoMqAutoConfiguration;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.WithdrawRequestedEvent;
import com.bingo789.risk.service.WithdrawAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Withdrawal audit. Idempotent per order (risk_decision.order_no). */
@Component
@RequiredArgsConstructor
public class WithdrawRequestedHandler {

    private final WithdrawAuditService auditService;

    @KafkaListener(topics = Topics.WITHDRAW_REQUESTED, groupId = "bingo-risk-withdraw",
            containerFactory = BingoMqAutoConfiguration.BIZ_EVENTS)
    public void onMessage(String payload) {
        auditService.onWithdrawRequested(JsonUtils.fromJson(payload, WithdrawRequestedEvent.class));
    }
}
