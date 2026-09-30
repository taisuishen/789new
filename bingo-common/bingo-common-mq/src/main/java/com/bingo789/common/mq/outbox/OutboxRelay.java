package com.bingo789.common.mq.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls pending outbox rows and publishes them to Kafka (acks=all, waits for the broker). {@code FOR UPDATE SKIP
 * LOCKED} lets every replica run the relay concurrently without double-sending the same row. Consumers are
 * idempotent anyway, because a crash between send and commit re-sends the row.
 */
@Slf4j
public class OutboxRelay implements SmartLifecycle {

    private static final String SELECT_SQL = """
            SELECT id, topic, msg_key, payload, retry_count FROM mq_outbox
             WHERE status = 0 AND next_retry_at <= NOW(3)
             ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
            """;
    private static final String MARK_SENT_SQL = "UPDATE mq_outbox SET status = 1, sent_at = NOW(3) WHERE id = ?";
    /** MySQL evaluates SET assignments left to right, so retry_count must be incremented last. */
    private static final String MARK_RETRY_SQL = """
            UPDATE mq_outbox SET status = IF(retry_count + 1 >= ?, 2, 0),
                   next_retry_at = DATE_ADD(NOW(3), INTERVAL LEAST(POW(2, retry_count), 300) SECOND),
                   retry_count = retry_count + 1
             WHERE id = ?
            """;

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private ScheduledExecutorService scheduler;

    public OutboxRelay(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                       KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    void relayOnce() {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                List<Row> rows = jdbcTemplate.query(SELECT_SQL, (rs, i) -> new Row(
                        rs.getLong("id"), rs.getString("topic"), rs.getString("msg_key"), rs.getString("payload"),
                        rs.getInt("retry_count")), properties.batchSize());
                for (Row row : rows) {
                    try {
                        kafkaTemplate.send(row.topic(), row.key(), row.payload()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        jdbcTemplate.update(MARK_SENT_SQL, row.id());
                    } catch (Exception e) {
                        jdbcTemplate.update(MARK_RETRY_SQL, properties.maxRetries(), row.id());
                        if (row.retryCount() + 1 >= properties.maxRetries()) {
                            log.error("outbox message {} moved to FAILED after {} retries, key={}", row.id(), row.retryCount() + 1, row.key(), e);
                        } else {
                            log.warn("outbox send failed, id={}, key={}", row.id(), row.key(), e);
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.error("outbox relay iteration failed", e);
        }
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("outbox-relay").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::relayOnce, properties.relayIntervalMs(), properties.relayIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private record Row(long id, String topic, String key, String payload, int retryCount) {
    }
}
