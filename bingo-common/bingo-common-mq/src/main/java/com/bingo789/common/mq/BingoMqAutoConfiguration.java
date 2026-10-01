package com.bingo789.common.mq;

import com.bingo789.common.core.trace.TraceContext;
import com.bingo789.common.mq.outbox.OutboxProperties;
import com.bingo789.common.mq.outbox.OutboxRelay;
import com.bingo789.common.mq.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * Messaging is Kafka only: data streams (bingo.wallet.txn, ...) and business events (deposits, withdrawals,
 * bonuses) written through the transactional outbox. Runs after Boot's Kafka auto-configuration so the
 * KafkaTemplate / ConsumerFactory it provides are available.
 */
@AutoConfiguration(afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@EnableConfigurationProperties(OutboxProperties.class)
public class BingoMqAutoConfiguration {

    /** Name of the listener container factory for business events: {@code containerFactory = BIZ_EVENTS}. */
    public static final String BIZ_EVENTS = "bizEventListenerFactory";

    /** 1 + 2 + 4 + ... + 32 s, then one attempt a minute: the 15th attempt is about 10 minutes after the first. */
    static final int ALERT_AFTER_ATTEMPTS = 15;

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnProperty(prefix = "bingo.outbox", name = "enabled", havingValue = "true")
    static class OutboxConfiguration {

        @Bean
        OutboxService outboxService(JdbcTemplate jdbcTemplate) {
            return new OutboxService(jdbcTemplate);
        }

        @Bean
        @ConditionalOnBean(KafkaTemplate.class)
        @ConditionalOnProperty(prefix = "bingo.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
        OutboxRelay outboxRelay(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                                KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
            return new OutboxRelay(jdbcTemplate, transactionManager, kafkaTemplate, properties);
        }
    }

    /**
     * Listener factory for business events (one record at a time).
     * <ul>
     *   <li>A failing record is retried until it succeeds (1 s doubling up to 1 min): a deposit, withdrawal or bonus
     *   event that is skipped is a missing wagering requirement or a wrong balance, so the partition waits instead.
     *   From the {@value #ALERT_AFTER_ATTEMPTS}th attempt (~10 minutes) every failure is logged as ALERT; fix the cause
     *   (usually a dependency outage or a bug) and the record goes through on its own.</li>
     *   <li>Only records that can never succeed, i.e. unparseable payloads, go to {@code <topic>.DLT} at once (ALERT),
     *   and the partition moves on. Replay a DLT record by re-publishing it to its topic: every consumer is idempotent
     *   on the message key.</li>
     * </ul>
     */
    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.kafka.core.KafkaTemplate",
            "org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer"})
    @ConditionalOnBean({ConsumerFactory.class, KafkaTemplate.class, ConcurrentKafkaListenerContainerFactoryConfigurer.class})
    static class BizEventListenerConfiguration {

        @Bean(BIZ_EVENTS)
        ConcurrentKafkaListenerContainerFactory<Object, Object> bizEventListenerFactory(
                ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
                KafkaTemplate<Object, Object> kafkaTemplate) {
            ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
            backOff.setMaxInterval(60_000L);
            backOff.setMaxElapsedTime(Long.MAX_VALUE);
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
            DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, e) -> {
                log.error("ALERT unparseable business event parked on {}.DLT: key={}", record.topic(), record.key(), e);
                recoverer.accept(record, e);
            }, backOff);
            errorHandler.addNotRetryableExceptions(JacksonException.class);
            errorHandler.setRetryListeners((record, e, deliveryAttempt) -> {
                if (deliveryAttempt >= ALERT_AFTER_ATTEMPTS) {
                    log.error("ALERT business event on {}-{}@{} still failing after {} attempts, key={}", record.topic(),
                            record.partition(), record.offset(), deliveryAttempt, record.key(), e);
                }
            });

            // spring.kafka.listener.* (auto-startup, concurrency, ...) as for Boot's default factory, then our policy
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
            configurer.configure(factory, consumerFactory);
            factory.setCommonErrorHandler(errorHandler);
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            factory.setRecordInterceptor(new TraceByKey());
            return factory;
        }
    }

    /** Uses the message key (order no / biz no) as trace id while the record is handled. */
    static final class TraceByKey implements RecordInterceptor<Object, Object> {

        @Override
        public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            TraceContext.set(record.key() != null ? record.key().toString()
                    : record.topic() + "-" + record.partition() + "-" + record.offset());
            return record;
        }

        @Override
        public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            TraceContext.clear();
        }
    }
}
