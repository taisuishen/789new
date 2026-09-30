package com.bingo789.user.service;

import com.bingo789.common.core.session.SessionKeys;
import com.bingo789.user.config.SessionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The gateway reads the session on every request, so deleting the keys is the whole revocation. */
class SessionServiceTest {

    private static final String TOKEN_A = "a".repeat(43);
    private static final String TOKEN_B = "b".repeat(43);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private SessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        service = new SessionService(redis, new SessionProperties(Duration.ofHours(12), 5, Duration.ofMinutes(15)));
    }

    @Test
    void revokeDeletesTheSessionAndItsIndexEntry() {
        when(values.get(SessionKeys.session(TOKEN_A))).thenReturn("42");

        service.revoke(TOKEN_A);

        verify(redis).delete(SessionKeys.session(TOKEN_A));
        verify(sets).remove(SessionKeys.userSessions(42), TOKEN_A);
    }

    @Test
    void revokeAllDeletesEverySessionOfThePlayer() {
        Set<String> tokens = new LinkedHashSet<>(List.of(TOKEN_A, TOKEN_B));
        when(sets.members(SessionKeys.userSessions(42))).thenReturn(tokens);

        service.revokeAll(42);

        verify(redis).delete(List.of(SessionKeys.session(TOKEN_A), SessionKeys.session(TOKEN_B)));
        verify(sets).remove(SessionKeys.userSessions(42), TOKEN_A, TOKEN_B);
    }

    @Test
    void revokeAllWithoutSessionsDeletesNothing() {
        when(sets.members(SessionKeys.userSessions(42))).thenReturn(Set.of());

        service.revokeAll(42);

        verify(redis, never()).delete(any(java.util.Collection.class));
    }

    @Test
    void sessionIdIsAStableTruncatedSha256() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertThat(SessionKeys.sessionId("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223");
    }
}
