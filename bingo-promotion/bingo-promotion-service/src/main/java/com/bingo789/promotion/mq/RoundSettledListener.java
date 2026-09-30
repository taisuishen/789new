package com.bingo789.promotion.mq;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.promotion.service.ValidBetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Valid-bet statistics from the round-settled stream; a batch is applied atomically and retried on failure. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoundSettledListener {

    private final ValidBetService validBetService;

    @KafkaListener(topics = Topics.ROUND_SETTLED, groupId = "bingo-promotion-validbet", batch = "true")
    public void onRounds(List<String> payloads) {
        List<RoundSettledEvent> events = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            if (payload == null) {
                continue;
            }
            try {
                events.add(JsonUtils.fromJson(payload, RoundSettledEvent.class));
            } catch (RuntimeException e) {
                // a poison message must not stall the partition; it stays in Kafka for investigation
                log.error("ALERT skipping malformed {} message: {}", Topics.ROUND_SETTLED,
                        payload.length() > 500 ? payload.substring(0, 500) : payload, e);
            }
        }
        validBetService.applySettledRounds(events);
    }
}
