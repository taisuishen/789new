package com.bingo789.user.service;

import com.bingo789.common.core.BizException;
import com.bingo789.user.UserErrorCode;
import com.bingo789.user.config.SessionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-username lock-out after repeated wrong passwords. Complements the per-IP rate limit at the gateway,
 * which alone does not stop credential stuffing from many IPs against one account.
 */
@Component
@RequiredArgsConstructor
public class LoginThrottle {

    private static final String KEY_PREFIX = "bingo:user:login-fail:";

    private final StringRedisTemplate redis;
    private final SessionProperties properties;

    public void checkAllowed(String username) {
        String failures = redis.opsForValue().get(KEY_PREFIX + username);
        if (failures != null && Long.parseLong(failures) >= properties.maxFailedLogins()) {
            throw new BizException(UserErrorCode.LOGIN_TEMPORARILY_LOCKED);
        }
    }

    public void recordFailure(String username) {
        String key = KEY_PREFIX + username;
        Long failures = redis.opsForValue().increment(key);
        if (failures != null && failures == 1L) {
            redis.expire(key, properties.failedLoginWindow());
        }
    }

    public void reset(String username) {
        redis.delete(KEY_PREFIX + username);
    }
}
