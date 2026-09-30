package com.bingo789.reconcile.ingest;

import com.bingo789.common.mq.Topics;
import com.bingo789.common.mybatis.MasterRoute;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Provider side: pulled provider bet records -> recon_provider_hourly (by the record's bet time hour). */
@Component
@RequiredArgsConstructor
public class ProviderBetConsumer implements ConsumerSeekAware {

    static final String GROUP = "bingo-reconcile-provider";

    private final AggregationService aggregationService;
    private final KafkaOffsetStore offsetStore;

    @KafkaListener(id = GROUP, groupId = GROUP, topics = Topics.PROVIDER_BET, batch = "true",
            concurrency = "${bingo.reconcile.consumer-concurrency:3}")
    public void onMessages(List<ConsumerRecord<String, String>> records) {
        MasterRoute.run(() -> {
            aggregationService.applyProviderBatch(GROUP, Topics.PROVIDER_BET, records);
            return null;
        });
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        offsetStore.seekToStored(GROUP, assignments.keySet(), callback);
    }
}
