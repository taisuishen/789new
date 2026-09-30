package com.bingo789.turnover.mq;

import com.bingo789.common.core.game.TurnoverScope;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.mq.BingoMqAutoConfiguration;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.turnover.domain.SourceType;
import com.bingo789.turnover.service.BucketService;
import com.bingo789.turnover.service.BucketService.NewBucket;
import com.bingo789.turnover.service.SettingService;
import com.bingo789.turnover.service.Waterfall;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Deposit play-through (AML): every deposit creates an ALL-games bucket of amount x the line's deposit multiplier.
 * Idempotent per order number. (The large-deposit AML alert stays in risk, which consumes the same topic.)
 */
@Component
@RequiredArgsConstructor
public class DepositSucceededHandler {

    private final BucketService bucketService;
    private final SettingService settingService;

    @KafkaListener(topics = Topics.DEPOSIT_SUCCEEDED, groupId = "bingo-turnover-deposit",
            containerFactory = BingoMqAutoConfiguration.BIZ_EVENTS)
    public void onMessage(String payload) {
        handle(JsonUtils.fromJson(payload, DepositSucceededEvent.class));
    }

    void handle(DepositSucceededEvent event) {
        BigDecimal multiplier = settingService.rules().of(event.userLine(), event.currency()).depositMultiplier();
        Instant at = event.succeededAt() != null ? event.succeededAt() : Instant.now();
        bucketService.create(new NewBucket(event.userId(), event.userLine(), event.currency(), TurnoverScope.ALL, null,
                SourceType.DEPOSIT, event.orderNo(), event.amount(), multiplier, at, Waterfall.SYSTEM));
    }
}
