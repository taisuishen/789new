package com.bingo789.reconcile.ingest;

import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.reconcile.mapper.KafkaOffsetMapper;
import com.bingo789.reconcile.model.KafkaOffsetRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Exactly-once effect for aggregates fed by at-least-once Kafka: the next offset per partition is stored in the
 * service database and advanced in the same local transaction as the aggregates. Kafka's own committed offsets
 * are only a hint; on assignment the consumer seeks to the stored offsets.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaOffsetStore {

    private final KafkaOffsetMapper mapper;

    /** Called from ConsumerSeekAware.onPartitionsAssigned. */
    public void seekToStored(String group, Collection<TopicPartition> assigned, ConsumerSeekAware.ConsumerSeekCallback callback) {
        Map<String, List<TopicPartition>> byTopic = assigned.stream().collect(Collectors.groupingBy(TopicPartition::topic));
        for (Map.Entry<String, List<TopicPartition>> entry : byTopic.entrySet()) {
            String topic = entry.getKey();
            Set<Integer> partitions = entry.getValue().stream().map(TopicPartition::partition).collect(Collectors.toSet());
            try {
                List<KafkaOffsetRow> stored = MasterRoute.run(() -> mapper.find(group, topic, partitions));
                for (KafkaOffsetRow row : stored) {
                    callback.seek(topic, row.getPartitionNo(), row.getNextOffset());
                    log.info("{}: {}-{} seeks to stored offset {}", group, topic, row.getPartitionNo(), row.getNextOffset());
                }
            } catch (RuntimeException e) {
                // Safe to continue from Kafka's committed offsets: already-applied records are still skipped
                // inside the transaction (lockAndFilter), so this only costs re-reading.
                log.error("{}: could not load stored offsets for {}, continuing from committed offsets", group, topic, e);
            }
        }
    }

    /**
     * Locks the stored offsets of the batch's partitions and returns the records not applied yet, together with
     * the offsets to store. Must run inside the aggregate transaction; the caller then calls {@link #save}.
     */
    public Pending lockAndFilter(String group, String topic, List<ConsumerRecord<String, String>> records) {
        // sorted: lock rows in the same order in every transaction
        TreeSet<Integer> partitions = records.stream().map(ConsumerRecord::partition).collect(Collectors.toCollection(TreeSet::new));
        Map<Integer, Long> stored = new HashMap<>();
        for (KafkaOffsetRow row : mapper.lock(group, topic, partitions)) {
            stored.put(row.getPartitionNo(), row.getNextOffset());
        }
        List<ConsumerRecord<String, String>> fresh = new ArrayList<>(records.size());
        Map<Integer, Long> next = new TreeMap<>();
        int skipped = 0;
        for (ConsumerRecord<String, String> record : records) {
            Long applied = stored.get(record.partition());
            if (applied != null && record.offset() < applied) {
                skipped++;
                continue;
            }
            fresh.add(record);
            next.merge(record.partition(), record.offset() + 1, Math::max);
        }
        if (skipped > 0) {
            log.info("{}: skipped {} already applied records of {}", group, skipped, topic);
        }
        return new Pending(fresh, next);
    }

    public void save(String group, String topic, Map<Integer, Long> nextOffsets) {
        if (!nextOffsets.isEmpty()) {
            // MyBatis-Plus probes every Map parameter with containsKey("et"), which a TreeMap<Integer, ?> rejects
            // with a ClassCastException; the copy keeps the partition order.
            mapper.save(group, topic, new LinkedHashMap<>(nextOffsets));
        }
    }

    public record Pending(List<ConsumerRecord<String, String>> records, Map<Integer, Long> nextOffsets) {

        public boolean isEmpty() {
            return records.isEmpty();
        }
    }
}
