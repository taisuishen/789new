package com.bingo789.turnover.mq;

import com.bingo789.common.core.game.TurnoverScope;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.mq.BingoMqAutoConfiguration;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.BonusGrantedEvent;
import com.bingo789.turnover.domain.SourceType;
import com.bingo789.turnover.service.BucketService;
import com.bingo789.turnover.service.BucketService.NewBucket;
import com.bingo789.turnover.service.Waterfall;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Bonus / rebate wagering requirement = amount x event.turnoverMultiplier (none when 0), scoped as the promotion
 * configured it (one game, one game type or all games). Idempotent per bizNo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BonusGrantedHandler {

    private static final String REBATE = "REBATE";

    private final BucketService bucketService;

    @KafkaListener(topics = Topics.BONUS_GRANTED, groupId = "bingo-turnover-bonus",
            containerFactory = BingoMqAutoConfiguration.BIZ_EVENTS)
    public void onMessage(String payload) {
        handle(JsonUtils.fromJson(payload, BonusGrantedEvent.class));
    }

    void handle(BonusGrantedEvent event) {
        TurnoverScope scope = TurnoverScope.ALL;
        String value = null;
        String error = TurnoverScope.validate(event.turnoverScope(), event.turnoverScopeValue());
        if (error == null) {
            scope = event.turnoverScope() == null ? TurnoverScope.ALL : TurnoverScope.valueOf(event.turnoverScope());
            value = event.turnoverScopeValue();
        } else {
            // promotion validates its config, so this is a producer bug; never lose the requirement over it
            log.error("ALERT bonus {} has an invalid wagering scope ({}), applying it to all games", event.bizNo(), error);
        }
        SourceType source = REBATE.equals(event.bonusType()) ? SourceType.REBATE : SourceType.BONUS;
        Instant at = event.grantedAt() != null ? event.grantedAt() : Instant.now();
        bucketService.create(new NewBucket(event.userId(), event.userLine(), event.currency(), scope, value,
                source, event.bizNo(), event.amount(), event.turnoverMultiplier(), at, Waterfall.SYSTEM));
    }
}
