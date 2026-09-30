package com.bingo789.common.mq.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxRetries   sends after which a row is set to FAILED (status 2, alert)
 * @param relayEnabled run the relay in this process (switched off in tests that inspect the rows)
 */
@ConfigurationProperties("bingo.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("500") long relayIntervalMs,
        @DefaultValue("200") int batchSize,
        @DefaultValue("20") int maxRetries,
        @DefaultValue("true") boolean relayEnabled) {
}
