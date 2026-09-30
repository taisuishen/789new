package com.bingo789.common.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param mode      single | cluster (DCS cluster edition)
 * @param addresses redis://host:port, one entry for single mode
 */
@ConfigurationProperties("bingo.redisson")
public record RedissonProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("single") String mode,
        @DefaultValue("redis://127.0.0.1:6379") List<String> addresses,
        String password,
        @DefaultValue("0") int database) {
}
