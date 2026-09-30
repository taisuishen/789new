package com.bingo789.payment.common;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Short per-player mutex for check-then-insert sequences (e.g. deposit limit check + order insert).
 * Fails closed: if Redis is unavailable the action is refused rather than run unguarded.
 * Not used on the wallet money path, which relies on conditional updates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserActionLock {

    private static final String KEY_PREFIX = "bingo:payment:lock:";
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public <T> T withLock(String action, long userId, Duration ttl, Supplier<T> body) {
        String key = KEY_PREFIX + action + ":" + userId;
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        } catch (RuntimeException e) {
            log.warn("lock {} unavailable", key, e);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        try {
            return body.get();
        } finally {
            try {
                redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
            } catch (RuntimeException e) {
                log.warn("failed to release lock {}; it expires after {}", key, ttl, e);
            }
        }
    }
}
