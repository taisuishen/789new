package com.bingo789.turnover.mq;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.turnover.service.WagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Wagering progress from the round-settled stream (message key = userId, so one player's rounds stay on one
 * partition and are applied in order). Scaled by KEDA on this group's lag (deploy/k8s/autoscaling-keda.yaml).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoundSettledListener {

    private final WagerService wagerService;

    @KafkaListener(id = "turnover-round-settled", topics = Topics.ROUND_SETTLED, groupId = "bingo-turnover",
            batch = "true", concurrency = "${bingo.turnover.consumer-concurrency:3}")
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
        wagerService.applySettledRounds(events);
    }
}
