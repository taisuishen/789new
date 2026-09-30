package com.bingo789.promotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class PromotionConfig {

    /** Explicit transaction boundaries: remote wallet calls must always run outside a DB transaction. */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Picked up by Boot's default Kafka listener container factory. A batch that cannot be applied is retried with
     * back-off indefinitely (consumer lag alerts fire) instead of being skipped, which would silently lose valid bet
     * and therefore rebates. Malformed records are dropped by the listener itself.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(5_000L, Long.MAX_VALUE));
    }
}
