package com.bingo789.gateway.admission;

import com.bingo789.gateway.config.BingoGatewayProperties;
import com.bingo789.gateway.support.LogThrottle;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cluster-wide waiting-room state in Redis:
 * <ul>
 *   <li>{@code bingo:wr:ticket}: INCR per queued request (the ticket number);</li>
 *   <li>{@code bingo:wr:admitted}: tickets up to this number may enter; advanced once per second by the leader
 *       by {@link AdmissionMath#admitStep};</li>
 *   <li>{@code bingo:wr:leader}: {@code SET NX PX 1500} lease, renewed every second by its holder (Lua, so only
 *       the holder can renew), so exactly one pod advances {@code admitted}.</li>
 * </ul>
 * Every pod reads both counters once per second and serves positions from memory. Every operation is single-key
 * (Redis Cluster safe). Redis failures fail open: no estimate means no gating, a failed INCR means the request is
 * admitted; both are counted in {@code bingo.gateway.admission.redis.failures}.
 */
@Slf4j
@Component
public class WaitingRoom {

    /** Served by AdmissionGlobalFilter; always public. */
    public static final String QUEUE_STATUS_PATH = "/api/queue/status";

    static final String TICKET_KEY = "bingo:wr:ticket";
    static final String ADMITTED_KEY = "bingo:wr:admitted";
    static final String LEADER_KEY = "bingo:wr:leader";
    static final long LEADER_LEASE_MILLIS = 1500;

    /** Acquire (SET NX PX) or renew (PEXPIRE, holder only); returns 1 while this pod leads. */
    private static final RedisScript<Long> LEAD = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if not current then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    /**
     * admitted = min(admitted + step, cap), never decreasing; cap is the ticket value the leader read, so even two
     * leaders overlapping after a long GC pause cannot push admitted past the issued tickets.
     */
    private static final RedisScript<Long> ADVANCE = new DefaultRedisScript<>("""
            local admitted = tonumber(redis.call('GET', KEYS[1]) or '0')
            local target = math.min(admitted + tonumber(ARGV[1]), tonumber(ARGV[2]))
            if target > admitted then
              return redis.call('INCRBY', KEYS[1], target - admitted)
            end
            return admitted
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final OnlineTracker online;
    private final boolean enabled;
    /** Changeable at runtime from Nacos (AdmissionLimitsRefresher). */
    private volatile long maxOnline;
    private volatile int admitRatePerSecond;
    private final String podId = podId();

    private final AtomicLong ticket = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private volatile boolean leader;
    private final Counter queued;
    private final Counter ticketFailures;
    private final Counter tickFailures;
    private final LogThrottle ticketLog = new LogThrottle(Duration.ofSeconds(10));
    private final LogThrottle tickLog = new LogThrottle(Duration.ofSeconds(10));

    public WaitingRoom(ReactiveStringRedisTemplate redis, OnlineTracker online, BingoGatewayProperties properties,
                       MeterRegistry registry) {
        BingoGatewayProperties.Admission admission = properties.admission();
        this.redis = redis;
        this.online = online;
        this.enabled = admission.enabled();
        this.maxOnline = admission.maxOnline();
        this.admitRatePerSecond = admission.admitRatePerSecond();
        if (enabled && (maxOnline < 1 || admitRatePerSecond < 1)) {
            throw new IllegalStateException("bingo.gateway.admission.max-online and admit-rate-per-second must be positive");
        }
        Gauge.builder("bingo.gateway.admission.queue.length", this, WaitingRoom::queueLength)
                .description("Players waiting in the queue (ticket - admitted)")
                .register(registry);
        Gauge.builder("bingo.gateway.admission.leader", this, room -> room.leader ? 1 : 0)
                .description("1 while this pod advances the queue")
                .register(registry);
        this.queued = Counter.builder("bingo.gateway.admission.queued")
                .description("Requests to protected paths answered with a queue ticket")
                .register(registry);
        this.ticketFailures = AdmissionMetrics.redisFailures(registry, "ticket");
        this.tickFailures = AdmissionMetrics.redisFailures(registry, "tick");
    }

    public boolean enabled() {
        return enabled;
    }

    /** Fails open: without a fresh estimate (Redis trouble, startup) the platform is never "at capacity". */
    public boolean atCapacity() {
        return enabled && online.hasFreshEstimate() && online.estimate() >= maxOnline;
    }

    public int admitRatePerSecond() {
        return admitRatePerSecond;
    }

    public long maxOnline() {
        return maxOnline;
    }

    /** Runtime change (e.g. lower the admission rate during an incident); both values must be positive. */
    public void updateLimits(long maxOnline, int admitRatePerSecond) {
        if (maxOnline < 1 || admitRatePerSecond < 1) {
            throw new IllegalArgumentException("max-online and admit-rate-per-second must be positive");
        }
        this.maxOnline = maxOnline;
        this.admitRatePerSecond = admitRatePerSecond;
    }

    /** Position from the counters cached by the last tick (at most ~1s old). */
    public long position(long ticketNumber) {
        return AdmissionMath.position(ticketNumber, admitted.get());
    }

    /** @return the new ticket number, or 0 when Redis failed (the caller admits: fail open) */
    public Mono<Long> nextTicket() {
        return redis.opsForValue().increment(TICKET_KEY)
                .doOnNext(number -> {
                    queued.increment();
                    ticket.accumulateAndGet(number, Math::max);
                })
                .onErrorResume(e -> {
                    ticketFailures.increment();
                    if (ticketLog.tryAcquire()) {
                        log.warn("queue ticket failed, admitting (logged at most every 10s): {}", e.toString());
                    }
                    return Mono.empty();
                })
                .defaultIfEmpty(0L);
    }

    /** Once per second on every pod: refresh the counters, then lead (advance admitted) if holding the lease. */
    Mono<Void> tick() {
        return Mono.zip(counter(TICKET_KEY), counter(ADMITTED_KEY))
                .doOnNext(counters -> {
                    ticket.set(counters.getT1());
                    admitted.set(counters.getT2());
                })
                .then(Mono.defer(this::leadAndAdvance))
                .onErrorResume(e -> {
                    leader = false;
                    tickFailures.increment();
                    if (tickLog.tryAcquire()) {
                        log.warn("waiting-room tick failed (logged at most every 10s): {}", e.toString());
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> leadAndAdvance() {
        return redis.execute(LEAD, List.of(LEADER_KEY), List.of(podId, Long.toString(LEADER_LEASE_MILLIS)))
                .next()
                .flatMap(held -> {
                    leader = held == 1L;
                    if (!leader) {
                        return Mono.empty();
                    }
                    long cap = ticket.get();
                    long estimate = online.hasFreshEstimate() ? online.estimate() : -1;
                    long step = AdmissionMath.admitStep(cap, admitted.get(), estimate, maxOnline, admitRatePerSecond);
                    if (step <= 0) {
                        return Mono.empty();
                    }
                    return redis.execute(ADVANCE, List.of(ADMITTED_KEY), List.of(Long.toString(step), Long.toString(cap)))
                            .next()
                            .doOnNext(admitted::set)
                            .then();
                });
    }

    private Mono<Long> counter(String key) {
        return redis.opsForValue().get(key).map(Long::parseLong).defaultIfEmpty(0L);
    }

    private long queueLength() {
        return Math.max(0, ticket.get() - admitted.get());
    }

    /** Unique per process: the pod name alone would be reused by a restarted pod that still "holds" the lease. */
    private static String podId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "gateway";
            }
        }
        return host + "/" + UUID.randomUUID();
    }
}
