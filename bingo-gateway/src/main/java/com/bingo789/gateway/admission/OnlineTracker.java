package com.bingo789.gateway.admission;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.gateway.config.BingoGatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cluster-wide estimate of online players: distinct userIds with authenticated traffic within the online window.
 * <p>
 * Requests only touch a pod-local set per minute. Every {@link #FLUSH_INTERVAL} each pod adds its sets to the
 * Redis HyperLogLog {@code bingo:online:<yyyyMMddHHmm>} (UTC+8 minute) with one PFADD per key (chunked, see
 * {@link #MAX_MEMBERS_PER_PFADD}), so Redis sees a few commands per pod every 10s instead of one per request. The
 * estimate is a PFCOUNT over the window's minute keys, refreshed every {@link #ESTIMATE_INTERVAL} and served from
 * memory. Failures only lower or age the estimate, i.e. fail towards admitting.
 * <p>
 * Redis Cluster note: a multi-key PFCOUNT needs all keys in one slot. The current DCS deployment is a single
 * endpoint; on a sharded cluster, add a hash tag to {@link #KEY_PREFIX} (writes are batched, so one slot is fine).
 */
@Slf4j
@Component
public class OnlineTracker {

    static final String KEY_PREFIX = "bingo:online:";
    static final Duration FLUSH_INTERVAL = Duration.ofSeconds(10);
    static final Duration ESTIMATE_INTERVAL = Duration.ofSeconds(5);
    /** Older estimates are ignored (admission fails open). */
    static final Duration ESTIMATE_MAX_AGE = ESTIMATE_INTERVAL.multipliedBy(3);
    /** Caps a single PFADD so it never blocks Redis for more than about a millisecond. */
    static final int MAX_MEMBERS_PER_PFADD = 10_000;
    private static final Duration MIN_KEY_TTL = Duration.ofMinutes(10);
    private static final DateTimeFormatter MINUTE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(BingoTime.ZONE);

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final int windowMinutes;
    private final Duration keyTtl;
    /** epoch minute -> userIds seen by this pod and not yet flushed */
    private final ConcurrentHashMap<Long, Set<String>> pending = new ConcurrentHashMap<>();
    private final AtomicLong estimate = new AtomicLong();
    private volatile long estimatedAtMillis = Long.MIN_VALUE;
    private final Counter flushFailures;
    private final Counter estimateFailures;

    public OnlineTracker(ReactiveStringRedisTemplate redis, BingoGatewayProperties properties, Clock clock,
                         MeterRegistry registry) {
        this.redis = redis;
        this.clock = clock;
        this.windowMinutes = (int) Math.max(1, (properties.admission().onlineWindow().toSeconds() + 59) / 60);
        this.keyTtl = max(MIN_KEY_TTL, Duration.ofMinutes(windowMinutes + 2L));
        Gauge.builder("bingo.gateway.admission.online", estimate, AtomicLong::get)
                .description("Estimated distinct players online (HyperLogLog over the online window)")
                .register(registry);
        this.flushFailures = AdmissionMetrics.redisFailures(registry, "online-flush");
        this.estimateFailures = AdmissionMetrics.redisFailures(registry, "online-estimate");
    }

    /** Hot path: one set insert, no I/O. */
    public void record(String userId) {
        long minute = clock.millis() / 60_000;
        Set<String> users = pending.get(minute);
        if (users == null) {
            users = pending.computeIfAbsent(minute, m -> ConcurrentHashMap.newKeySet());
        }
        users.add(userId);
    }

    public long estimate() {
        return estimate.get();
    }

    public boolean hasFreshEstimate() {
        long at = estimatedAtMillis;
        return at != Long.MIN_VALUE && clock.millis() - at <= ESTIMATE_MAX_AGE.toMillis();
    }

    /**
     * An id recorded concurrently with the swap may be lost; the player's next request records it again. A failed
     * batch is dropped (the estimate is then slightly low, i.e. errs towards admitting).
     */
    Mono<Void> flush() {
        return Flux.fromIterable(List.copyOf(pending.keySet()))
                .concatMap(minute -> {
                    Set<String> users = pending.remove(minute);
                    if (users == null || users.isEmpty()) {
                        return Mono.empty();
                    }
                    String key = key(minute);
                    return Flux.fromIterable(chunks(users.toArray(String[]::new), MAX_MEMBERS_PER_PFADD))
                            .concatMap(chunk -> redis.opsForHyperLogLog().add(key, chunk))
                            .then(redis.expire(key, keyTtl))
                            .then()
                            .onErrorResume(e -> {
                                flushFailures.increment();
                                log.warn("online flush failed for {} ({} players dropped): {}", key, users.size(), e.toString());
                                return Mono.empty();
                            });
                })
                .then();
    }

    Mono<Void> refreshEstimate() {
        long nowMinute = clock.millis() / 60_000;
        String[] keys = windowKeys(nowMinute, windowMinutes).toArray(String[]::new);
        return redis.opsForHyperLogLog().size(keys)
                .doOnNext(this::updateEstimate)
                .then()
                .onErrorResume(e -> {
                    estimateFailures.increment();
                    log.warn("online estimate failed, admission fails open once it is stale: {}", e.toString());
                    return Mono.empty();
                });
    }

    void updateEstimate(long value) {
        estimate.set(value);
        estimatedAtMillis = clock.millis();
    }

    static String key(long epochMinute) {
        return KEY_PREFIX + MINUTE_FORMAT.format(Instant.ofEpochSecond(epochMinute * 60));
    }

    /** Keys of the last {@code windowMinutes} minutes, current (partial) minute included. */
    static List<String> windowKeys(long nowEpochMinute, int windowMinutes) {
        List<String> keys = new ArrayList<>(windowMinutes);
        for (int i = 0; i < windowMinutes; i++) {
            keys.add(key(nowEpochMinute - i));
        }
        return keys;
    }

    static List<String[]> chunks(String[] members, int size) {
        List<String[]> chunks = new ArrayList<>((members.length + size - 1) / size);
        for (int from = 0; from < members.length; from += size) {
            chunks.add(Arrays.copyOfRange(members, from, Math.min(members.length, from + size)));
        }
        return chunks;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
