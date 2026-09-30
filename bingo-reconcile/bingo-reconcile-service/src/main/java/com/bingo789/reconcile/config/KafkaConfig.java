package com.bingo789.reconcile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /**
     * Picked up by Boot's listener container factory. A failed batch rolled its transaction back (aggregates and
     * offsets together), so retrying it is always safe; it is retried with capped exponential back-off and never
     * skipped, because a skipped batch would silently become a reconciliation difference.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return new DefaultErrorHandler(backOff);
    }
}
