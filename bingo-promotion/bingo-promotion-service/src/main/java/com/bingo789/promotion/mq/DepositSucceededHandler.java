package com.bingo789.promotion.mq;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.mq.BingoMqAutoConfiguration;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.promotion.service.BonusGrantService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** First-deposit bonus. Idempotent per deposit order (bonus_grant.biz_no). */
@Component
@RequiredArgsConstructor
public class DepositSucceededHandler {

    private final BonusGrantService bonusGrantService;

    @KafkaListener(topics = Topics.DEPOSIT_SUCCEEDED, groupId = "bingo-promotion-deposit",
            containerFactory = BingoMqAutoConfiguration.BIZ_EVENTS)
    public void onMessage(String payload) {
        bonusGrantService.onFirstDeposit(JsonUtils.fromJson(payload, DepositSucceededEvent.class));
    }
}
