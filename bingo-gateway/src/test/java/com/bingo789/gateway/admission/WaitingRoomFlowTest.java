package com.bingo789.gateway.admission;

import com.bingo789.common.core.HeaderNames;
import com.bingo789.common.core.Result;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.gateway.filter.AdmissionGlobalFilter;
import com.bingo789.gateway.support.GatewayErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The waiting room end to end through AdmissionGlobalFilter, with Redis mocked. */
class WaitingRoomFlowTest {

    private static final String USER = "1001";
    private static final String IP = "203.0.113.7";

    private ReactiveValueOperations<String, String> values;
    private OnlineTracker online;
    private WaitingRoom room;
    private AdmissionGlobalFilter filter;
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        // not the leader: admitted only changes through what the test puts into Redis
        when(redis.execute(any(RedisScript.class), eq(List.of(WaitingRoom.LEADER_KEY)), anyList()))
                .thenReturn(Flux.just(0L));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T04:00:00Z"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        online = new OnlineTracker(redis, OnlineTrackerTest.properties(), clock, registry);
        room = new WaitingRoom(redis, online, OnlineTrackerTest.properties(), registry);
        filter = new AdmissionGlobalFilter(room, online, OnlineTrackerTest.properties(), clock);
    }

    @Test
    void belowCapacityEntriesPassAndReceiveAPass() {
        online.updateEstimate(999);

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/user/login").header(HeaderNames.CLIENT_IP, IP));
        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(AdmissionGlobalFilter.ADMISSION_PASS_HEADER)).startsWith("P.0.");
    }

    @Test
    void atCapacityNewEntriesQueueUntilAdmitted() {
        online.updateEstimate(1_000);
        setCounters(0, 0);
        when(values.increment(WaitingRoom.TICKET_KEY)).thenReturn(Mono.just(5L));

        // 1. login at capacity -> 429 with a signed ticket
        MockServerWebExchange login = exchange(MockServerHttpRequest.post("/api/user/login").header(HeaderNames.CLIENT_IP, IP));
        filter.filter(login, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(login.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        Result<QueueTicketView> queued = body(login, new TypeReference<>() {
        });
        assertThat(queued.code()).isEqualTo(GatewayErrorCode.ADMISSION_QUEUED.code());
        assertThat(queued.data().position()).isEqualTo(5);
        String ticket = queued.data().ticket();

        // 2. not yet admitted: position only
        setCounters(5, 3);
        Result<QueueStatusView> waiting = poll(ticket, IP);
        assertThat(waiting.data().position()).isEqualTo(2);
        assertThat(waiting.data().pass()).isNull();

        // 3. admitted: the poll returns a pass
        setCounters(5, 5);
        Result<QueueStatusView> admitted = poll(ticket, IP);
        assertThat(admitted.data().position()).isZero();
        String pass = admitted.data().pass();
        assertThat(pass).startsWith("P.5.");

        // 4. the retry with the pass enters even at capacity; the pass is not forwarded upstream
        MockServerWebExchange retry = exchange(MockServerHttpRequest.post("/api/user/login")
                .header(HeaderNames.CLIENT_IP, IP)
                .header(AdmissionGlobalFilter.ADMISSION_PASS_HEADER, pass));
        filter.filter(retry, chain).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(AdmissionGlobalFilter.ADMISSION_PASS_HEADER)).isNull();
        assertThat(retry.getResponse().getHeaders().getFirst(AdmissionGlobalFilter.ADMISSION_PASS_HEADER)).startsWith("P.5.");
    }

    @Test
    void ticketAndPassDoNotTravelToAnotherClient() {
        online.updateEstimate(1_000);
        setCounters(5, 5);
        when(values.increment(WaitingRoom.TICKET_KEY)).thenReturn(Mono.just(5L));
        MockServerWebExchange login = exchange(MockServerHttpRequest.post("/api/user/login").header(HeaderNames.CLIENT_IP, IP));
        filter.filter(login, chain).block();
        String ticket = body(login, new TypeReference<Result<QueueTicketView>>() {
        }).data().ticket();

        MockServerWebExchange stolen = exchange(MockServerHttpRequest.get(WaitingRoom.QUEUE_STATUS_PATH + "?ticket=" + ticket)
                .header(HeaderNames.CLIENT_IP, "198.51.100.1"));
        filter.filter(stolen, chain).block();
        assertThat(stolen.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String pass = poll(ticket, IP).data().pass();
        MockServerWebExchange replay = exchange(MockServerHttpRequest.post("/api/user/login")
                .header(HeaderNames.CLIENT_IP, "198.51.100.1")
                .header(AdmissionGlobalFilter.ADMISSION_PASS_HEADER, pass));
        filter.filter(replay, chain).block();
        assertThat(replay.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    void playerAdmittedBeforeCapacityKeepsLaunchingGames() {
        online.updateEstimate(10);
        MockServerWebExchange first = exchange(MockServerHttpRequest.post("/api/lobby/games/7/launch")
                .header(HeaderNames.USER_ID, USER).header(HeaderNames.CLIENT_IP, IP));
        filter.filter(first, chain).block();
        String pass = first.getResponse().getHeaders().getFirst(AdmissionGlobalFilter.ADMISSION_PASS_HEADER);

        online.updateEstimate(1_000);
        forwarded.set(null);
        MockServerWebExchange second = exchange(MockServerHttpRequest.post("/api/lobby/games/8/launch")
                .header(HeaderNames.USER_ID, USER).header(HeaderNames.CLIENT_IP, "198.51.100.1")
                .header(AdmissionGlobalFilter.ADMISSION_PASS_HEADER, pass));
        filter.filter(second, chain).block();

        assertThat(forwarded.get()).isNotNull();
    }

    @Test
    void inGameTrafficIsNeverGated() {
        online.updateEstimate(5_000);

        filter.filter(exchange(MockServerHttpRequest.get("/api/wallet/balance")
                .header(HeaderNames.USER_ID, USER).header(HeaderNames.CLIENT_IP, IP)), chain).block();

        assertThat(forwarded.get()).isNotNull();
    }

    @Test
    void redisFailureAdmits() {
        online.updateEstimate(1_000);
        when(values.increment(WaitingRoom.TICKET_KEY)).thenReturn(Mono.error(new RedisConnectionFailureException("down")));

        filter.filter(exchange(MockServerHttpRequest.post("/api/user/login").header(HeaderNames.CLIENT_IP, IP)), chain).block();

        assertThat(forwarded.get()).isNotNull();
    }

    @Test
    void staleEstimateNeverGates() {
        // no estimate yet (startup, or PFCOUNT failing): fail open
        assertThat(room.atCapacity()).isFalse();
        online.updateEstimate(1_000);
        assertThat(room.atCapacity()).isTrue();
    }

    @Test
    void forgedTicketIsRejected() {
        MockServerWebExchange poll = exchange(MockServerHttpRequest.get(WaitingRoom.QUEUE_STATUS_PATH + "?ticket=T.1.9999999999.i.forged")
                .header(HeaderNames.CLIENT_IP, IP));

        filter.filter(poll, chain).block();

        assertThat(poll.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(poll, new TypeReference<Result<Void>>() {
        }).code()).isEqualTo(GatewayErrorCode.INVALID_QUEUE_TICKET.code());
    }

    private Result<QueueStatusView> poll(String ticket, String clientIp) {
        MockServerWebExchange poll = exchange(MockServerHttpRequest.get(WaitingRoom.QUEUE_STATUS_PATH + "?ticket=" + ticket)
                .header(HeaderNames.CLIENT_IP, clientIp));
        filter.filter(poll, chain).block();
        assertThat(poll.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        return body(poll, new TypeReference<>() {
        });
    }

    private void setCounters(long ticket, long admitted) {
        when(values.get(WaitingRoom.TICKET_KEY)).thenReturn(Mono.just(Long.toString(ticket)));
        when(values.get(WaitingRoom.ADMITTED_KEY)).thenReturn(Mono.just(Long.toString(admitted)));
        room.tick().block();
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        forwarded.set(null);
        return MockServerWebExchange.from(request);
    }

    private static <T> T body(MockServerWebExchange exchange, TypeReference<T> type) {
        return JsonUtils.fromJson(exchange.getResponse().getBodyAsString().block(), type);
    }
}
