package com.bingo789.common.mybatis.shard;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-based sharding. With {@code bingo.shard.enabled=false} (default, local development) the application keeps
 * its single Spring Boot DataSource and {@link ShardTemplate} is a pass-through, so the same code runs unchanged.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@EnableConfigurationProperties(ShardProperties.class)
public class BingoShardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ShardRouter shardRouter(ShardProperties properties) {
        return properties.enabled() ? ShardRouter.of(properties) : ShardRouter.single(properties.logicalShards());
    }

    @Bean
    @ConditionalOnMissingBean
    public ShardTemplate shardTemplate(ShardRouter router, ShardProperties properties) {
        return new ShardTemplate(router, properties.enabled());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "bingo.shard", name = "enabled", havingValue = "true")
    static class ShardedDataSourceConfiguration {

        /** Replaces Boot's DataSource; the transaction manager and MyBatis are built on top of it as usual. */
        @Bean
        @Primary
        public DataSource dataSource(ShardProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
            Map<Object, Object> targets = new LinkedHashMap<>();
            properties.datasources().forEach((name, spec) ->
                    targets.put(name, pool(name, spec, properties.pool(), meterRegistry.getIfAvailable())));
            ShardRoutingDataSource routing = new ShardRoutingDataSource();
            routing.setTargetDataSources(targets);
            routing.setLenientFallback(false);
            return routing;
        }

        private static HikariDataSource pool(String name, ShardProperties.DataSourceSpec spec, ShardProperties.Pool pool,
                                             MeterRegistry meterRegistry) {
            int maxPoolSize = spec.maxPoolSize() != null ? spec.maxPoolSize() : pool.maxPoolSize();
            HikariConfig config = new HikariConfig();
            config.setPoolName("shard-" + name);
            config.setJdbcUrl(spec.url());
            config.setUsername(spec.username());
            config.setPassword(spec.password());
            config.setMaximumPoolSize(maxPoolSize);
            config.setMinimumIdle(Math.min(pool.minIdle(), maxPoolSize));
            config.setConnectionTimeout(pool.connectionTimeout().toMillis());
            config.setKeepaliveTime(pool.keepaliveTime().toMillis());
            config.setMaxLifetime(pool.maxLifetime().toMillis());
            config.setTransactionIsolation(pool.transactionIsolation());
            // start even if one shard is down: only its users are affected, and they get retryable errors
            config.setInitializationFailTimeout(-1);
            if (meterRegistry != null) {
                config.setMetricRegistry(meterRegistry);
            }
            return new HikariDataSource(config);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cloud.context.environment.EnvironmentChangeEvent")
    @ConditionalOnProperty(prefix = "bingo.shard", name = "enabled", havingValue = "true")
    static class ShardReloadConfiguration {

        @Bean
        public ShardRoutesRefresher shardRoutesRefresher(ShardRouter router, Environment environment) {
            return new ShardRoutesRefresher(router, environment);
        }
    }
}
