package com.bingo789.risk.mq;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.mq.BingoMqAutoConfiguration;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.risk.service.AmlAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Large-deposit AML alert. Idempotent per order (aml_alert unique on type + ref). */
@Component
@RequiredArgsConstructor
public class DepositSucceededHandler {

    private final AmlAlertService amlAlertService;

    @KafkaListener(topics = Topics.DEPOSIT_SUCCEEDED, groupId = "bingo-risk-deposit",
            containerFactory = BingoMqAutoConfiguration.BIZ_EVENTS)
    public void onMessage(String payload) {
        amlAlertService.checkLargeDeposit(JsonUtils.fromJson(payload, DepositSucceededEvent.class));
    }
}
