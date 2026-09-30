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

/** Platform side: wallet ledger stream -> recon_platform_hourly (by the txn's createdAt hour). */
@Component
@RequiredArgsConstructor
public class PlatformLedgerConsumer implements ConsumerSeekAware {

    static final String GROUP = "bingo-reconcile-platform";

    private final AggregationService aggregationService;
    private final KafkaOffsetStore offsetStore;

    @KafkaListener(id = GROUP, groupId = GROUP, topics = Topics.WALLET_TXN, batch = "true",
            concurrency = "${bingo.reconcile.consumer-concurrency:3}")
    public void onMessages(List<ConsumerRecord<String, String>> records) {
        // offsets are read FOR UPDATE and must reflect the latest commit: pin to the primary
        MasterRoute.run(() -> {
            aggregationService.applyLedgerBatch(GROUP, Topics.WALLET_TXN, records);
            return null;
        });
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        offsetStore.seekToStored(GROUP, assignments.keySet(), callback);
    }
}
