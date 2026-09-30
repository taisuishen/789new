package com.bingo789.gateway.config;

import com.bingo789.common.core.HeaderNames;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /**
     * Key for the RequestRateLimiter default filter: the authenticated user, otherwise the client IP.
     * Both headers are written by AuthGlobalFilter, which runs first and drops any client-supplied copies.
     */
    @Bean
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            String userId = headers.getFirst(HeaderNames.USER_ID);
            if (userId != null && !userId.isEmpty()) {
                return Mono.just("user:" + userId);
            }
            String ip = headers.getFirst(HeaderNames.CLIENT_IP);
            return Mono.just("ip:" + (ip != null ? ip : "unknown"));
        };
    }
}
