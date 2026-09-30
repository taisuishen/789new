package com.bingo789.betrecord.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /**
     * Picked up by Boot's listener container factory. Unlimited exponential back-off (capped at 30s): a record
     * that cannot be applied blocks its partition instead of being dropped, and consumer-lag alerting surfaces it.
     * Malformed payloads are skipped by the listener itself, so only real processing errors end up here.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return new DefaultErrorHandler(backOff);
    }
}
