package com.bingo789.gateway.admission;

import com.bingo789.gateway.config.BingoGatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveHyperLogLogOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlineTrackerTest {

    /** 2026-09-30 08:00 in UTC+8 */
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");

    private ReactiveStringRedisTemplate redis;
    private ReactiveHyperLogLogOperations<String, String> hll;
    private MutableClock clock;
    private OnlineTracker tracker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        hll = mock(ReactiveHyperLogLogOperations.class);
        when(redis.opsForHyperLogLog()).thenReturn(hll);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(hll.add(anyString(), any(String[].class))).thenReturn(Mono.just(1L));
        clock = new MutableClock(NOW);
        tracker = new OnlineTracker(redis, properties(), clock, new SimpleMeterRegistry());
    }

    @Test
    void keysAreUtcPlus8Minutes() {
        long minute = NOW.getEpochSecond() / 60;

        assertThat(OnlineTracker.key(minute)).isEqualTo("bingo:online:202609300800");
        assertThat(OnlineTracker.windowKeys(minute, 3)).containsExactly(
                "bingo:online:202609300800", "bingo:online:202609300759", "bingo:online:202609300758");
    }

    @Test
    void flushBatchesDistinctUsersPerMinuteKey() {
        for (int i = 0; i < 25_000; i++) {
            tracker.record(Integer.toString(i));
            tracker.record(Integer.toString(i)); // duplicates are collapsed locally
        }
        clock.advance(Duration.ofMinutes(1));
        tracker.record("late");

        tracker.flush().block();

        // 25k members in chunks of 10k for 08:00, one PFADD for 08:01; nothing per request
        verify(hll, times(3)).add(eq("bingo:online:202609300800"), any(String[].class));
        verify(hll, times(1)).add(eq("bingo:online:202609300801"), any(String[].class));
        verify(redis).expire("bingo:online:202609300800", Duration.ofMinutes(10));

        // drained: a second flush sends nothing
        tracker.flush().block();
        verify(hll, times(4)).add(anyString(), any(String[].class));
    }

    @Test
    void estimateGoesStaleWithoutRefresh() {
        assertThat(tracker.hasFreshEstimate()).isFalse();

        tracker.updateEstimate(123);
        assertThat(tracker.hasFreshEstimate()).isTrue();
        assertThat(tracker.estimate()).isEqualTo(123);

        clock.advance(OnlineTracker.ESTIMATE_MAX_AGE.plusSeconds(1));
        assertThat(tracker.hasFreshEstimate()).isFalse();
    }

    @Test
    void chunksCoverEveryMember() {
        String[] members = {"a", "b", "c", "d", "e"};

        List<String[]> chunks = OnlineTracker.chunks(members, 2);

        assertThat(chunks).extracting(chunk -> chunk.length).containsExactly(2, 2, 1);
        assertThat(OnlineTracker.chunks(new String[0], 2)).isEmpty();
    }

    static BingoGatewayProperties properties() {
        return properties(true, 1_000);
    }

    static BingoGatewayProperties properties(boolean enabled, long maxOnline) {
        return new BingoGatewayProperties(
                new BingoGatewayProperties.Geo(false, "X-Country-Code", List.of(), List.of()),
                new BingoGatewayProperties.ClientIp(0),
                new BingoGatewayProperties.Auth(List.of()),
                new BingoGatewayProperties.Admission(enabled, maxOnline, Duration.ofMinutes(5),
                        List.of("/api/user/login", "/api/lobby/games/*/launch"), 2000,
                        Duration.ofMinutes(30), Duration.ofHours(1), "0123456789abcdef0123456789abcdef"),
                List.of());
    }
}
