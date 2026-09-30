package com.bingo789.user.service;

import com.bingo789.common.core.session.SessionKeys;
import com.bingo789.user.config.SessionProperties;
import com.bingo789.user.support.Tokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Server-side player sessions in Redis ({@link SessionKeys}). The gateway reads the session on every request, so
 * deleting the key revokes it at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final StringRedisTemplate redis;
    private final SessionProperties properties;

    public String create(long userId, Duration ttl) {
        String token = Tokens.newToken();
        String indexKey = SessionKeys.userSessions(userId);
        // Index first: a failure in between leaves a dangling index entry, never a session revoke-all cannot find.
        redis.opsForSet().add(indexKey, token);
        redis.expire(indexKey, properties.ttl());
        redis.opsForValue().set(SessionKeys.session(token), Long.toString(userId), ttl);
        return token;
    }

    public void refresh(long userId, String token) {
        redis.expire(SessionKeys.session(token), properties.ttl());
        redis.expire(SessionKeys.userSessions(userId), properties.ttl());
    }

    public void revoke(String token) {
        String sessionKey = SessionKeys.session(token);
        String userId = redis.opsForValue().get(sessionKey);
        redis.delete(sessionKey);
        if (userId != null) {
            redis.opsForSet().remove(SessionKeys.userSessions(Long.parseLong(userId)), token);
        }
    }

    /** Used by self-exclusion / cool-off (and, later, suspension). */
    public void revokeAll(long userId) {
        String indexKey = SessionKeys.userSessions(userId);
        Set<String> tokens = redis.opsForSet().members(indexKey);
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        redis.delete(tokens.stream().map(SessionKeys::session).toList());
        // Remove only what was read, so a login racing with this call keeps its index entry.
        redis.opsForSet().remove(indexKey, tokens.toArray());
    }
}
