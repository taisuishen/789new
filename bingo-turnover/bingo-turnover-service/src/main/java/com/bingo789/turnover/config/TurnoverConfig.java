package com.bingo789.turnover.config;

import com.bingo789.user.api.UserClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableFeignClients(clients = UserClient.class)
public class TurnoverConfig {

    /** Transactions are opened inside a shard scope (ShardTemplate), never around it. */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Picked up by Boot's default Kafka listener container factory. A batch that cannot be applied is retried with
     * back-off indefinitely (the partition stalls and the lag alert fires) instead of being skipped, which would
     * silently lose wagering progress. Malformed records are dropped by the listener itself.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2_000L, Long.MAX_VALUE));
    }
}
