package com.bingo789.common.mq.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param alertAfterRetries failed sends after which every further failure of a row is logged as ALERT (the row is
 *                          never given up: it keeps being retried every 5 minutes until Kafka accepts it)
 * @param keepSentDays      sent rows are purged after this many days
 * @param relayEnabled      run the relay in this process (switched off in tests that inspect the rows)
 */
@ConfigurationProperties("bingo.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("500") long relayIntervalMs,
        @DefaultValue("200") int batchSize,
        @DefaultValue("20") int alertAfterRetries,
        @DefaultValue("7") int keepSentDays,
        @DefaultValue("true") boolean relayEnabled) {
}
