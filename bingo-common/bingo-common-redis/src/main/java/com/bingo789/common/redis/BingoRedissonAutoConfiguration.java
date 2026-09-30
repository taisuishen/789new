package com.bingo789.common.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Redisson is used for distributed primitives (locks, semaphores, rate limiters) only.
 * It is never used on the money path: balances are decided by conditional updates in the database.
 */
@AutoConfiguration
@EnableConfigurationProperties(RedissonProperties.class)
@ConditionalOnProperty(prefix = "bingo.redisson", name = "enabled", havingValue = "true")
public class BingoRedissonAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(RedissonProperties properties) {
        Config config = new Config();
        String password = properties.password() == null || properties.password().isBlank() ? null : properties.password();
        if ("cluster".equalsIgnoreCase(properties.mode())) {
            config.useClusterServers()
                    .addNodeAddress(properties.addresses().toArray(String[]::new))
                    .setPassword(password);
        } else {
            config.useSingleServer()
                    .setAddress(properties.addresses().getFirst())
                    .setDatabase(properties.database())
                    .setPassword(password);
        }
        return Redisson.create(config);
    }
}
