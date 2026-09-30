package com.bingo789.common.mq.outbox;

import com.bingo789.common.core.json.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional outbox ("local transaction + reliable message"): the message row is written in the
 * same local transaction as the business change and relayed to Kafka by {@link OutboxRelay}.
 * Requires the {@code mq_outbox} table in the service's own database (see deploy/sql).
 */
public class OutboxService {

    private static final String INSERT_SQL = """
            INSERT INTO mq_outbox (topic, msg_key, payload, status, retry_count, next_retry_at, created_at)
            VALUES (?, ?, ?, 0, 0, NOW(3), NOW(3))
            """;

    private final JdbcTemplate jdbcTemplate;

    public OutboxService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Must be called inside the business transaction; fails fast otherwise. */
    /** @param key Kafka message key (order no / biz no) */
    public void save(String topic, String key, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxService.save must run inside the business transaction");
        }
        jdbcTemplate.update(INSERT_SQL, topic, key, JsonUtils.toJson(payload));
    }
}
