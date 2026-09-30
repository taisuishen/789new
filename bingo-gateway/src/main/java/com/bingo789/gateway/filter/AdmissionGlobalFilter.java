package com.bingo789.gateway.filter;

import com.bingo789.common.core.HeaderNames;
import com.bingo789.gateway.admission.AdmissionMath;
import com.bingo789.gateway.admission.AdmissionTokens;
import com.bingo789.gateway.admission.AdmissionTokens.Claims;
import com.bingo789.gateway.admission.AdmissionTokens.Kind;
import com.bingo789.gateway.admission.AdmissionTokens.Subject;
import com.bingo789.gateway.admission.OnlineTracker;
import com.bingo789.gateway.admission.QueueStatusView;
import com.bingo789.gateway.admission.QueueTicketView;
import com.bingo789.gateway.admission.WaitingRoom;
import com.bingo789.gateway.config.BingoGatewayProperties;
import com.bingo789.gateway.support.GatewayErrorCode;
import com.bingo789.gateway.support.GatewayResponses;
import com.bingo789.gateway.support.PathRules;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Admission control ("waiting room"). When the platform is at capacity, players already inside are unaffected and
 * only new entries (the protected paths: login, game launch) queue:
 * <ol>
 *   <li>At capacity, a protected request without a valid {@value #ADMISSION_PASS_HEADER} gets HTTP 429 with a
 *       signed queue ticket ({@link QueueTicketView}).</li>
 *   <li>The client polls {@code GET /api/queue/status?ticket=...} (public, answered here from memory, see
 *       {@link com.bingo789.gateway.admission.AdmissionConfig#waitingRoomRoute}); once its turn comes the answer
 *       carries a pass ({@link QueueStatusView}).</li>
 *   <li>The client retries with the pass, which is admitted even at capacity.</li>
 * </ol>
 * Every admitted protected request gets a refreshed pass in the {@value #ADMISSION_PASS_HEADER} response header, so
 * a player who entered while there was room keeps launching games when capacity is reached later.
 * <p>
 * Also records the userId of every authenticated request for the online estimate. Runs after AuthGlobalFilter,
 * which writes the trusted X-User-Id / X-Client-Ip. Redis failures fail open (admit).
 * TODO: expose X-Admission-Pass through CORS (Access-Control-Expose-Headers) together with globalcors.
 */
@Component
public class AdmissionGlobalFilter implements GlobalFilter, Ordered {

    public static final String ADMISSION_PASS_HEADER = "X-Admission-Pass";

    private final WaitingRoom waitingRoom;
    private final OnlineTracker onlineTracker;
    /** null while the waiting room is disabled */
    private final AdmissionTokens tokens;
    private final PathRules protectedPaths;
    private final Duration passTtl;
    private final Duration ticketTtl;
    private final Clock clock;

    public AdmissionGlobalFilter(WaitingRoom waitingRoom, OnlineTracker onlineTracker,
                                 BingoGatewayProperties properties, Clock clock) {
        BingoGatewayProperties.Admission admission = properties.admission();
        this.waitingRoom = waitingRoom;
        this.onlineTracker = onlineTracker;
        this.tokens = waitingRoom.enabled() ? tokens(admission.passSecret()) : null;
        this.protectedPaths = PathRules.of(admission.protectedPaths());
        this.passTtl = admission.passTtl();
        this.ticketTtl = admission.ticketTtl();
        this.clock = clock;
    }

    private static AdmissionTokens tokens(String secret) {
        try {
            return new AdmissionTokens(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("bingo.gateway.admission.pass-secret must be at least "
                    + AdmissionTokens.MIN_SECRET_LENGTH + " characters while the waiting room is enabled", e);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String userId = request.getHeaders().getFirst(HeaderNames.USER_ID);
        if (userId != null) {
            onlineTracker.record(userId);
        }
        if (isQueueStatus(request)) {
            return status(exchange, userId);
        }
        if (tokens == null || HttpMethod.OPTIONS.equals(request.getMethod()) || !protectedPaths.matches(request)) {
            return chain.filter(exchange);
        }
        String clientIp = request.getHeaders().getFirst(HeaderNames.CLIENT_IP);
        Instant now = clock.instant();
        Subject subject = Subject.of(userId, clientIp);
        Claims pass = tokens.verify(request.getHeaders().getFirst(ADMISSION_PASS_HEADER), Kind.PASS, now, userId, clientIp);
        if (pass != null || !waitingRoom.atCapacity()) {
            return admit(exchange, chain, pass != null ? pass.number() : 0, subject, now);
        }
        // nextTicket maps Redis errors to 0 before this flatMap, so a downstream error never re-runs the chain.
        return waitingRoom.nextTicket().flatMap(ticket -> ticket > 0
                ? queued(exchange, ticket, subject, now)
                : admit(exchange, chain, 0, subject, now));
    }

    private Mono<Void> admit(ServerWebExchange exchange, GatewayFilterChain chain, long ticket, Subject subject,
                             Instant now) {
        exchange.getResponse().getHeaders()
                .set(ADMISSION_PASS_HEADER, tokens.issue(Kind.PASS, ticket, now.plus(passTtl), subject));
        if (exchange.getRequest().getHeaders().getFirst(ADMISSION_PASS_HEADER) == null) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(ADMISSION_PASS_HEADER))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private Mono<Void> queued(ServerWebExchange exchange, long ticket, Subject subject, Instant now) {
        long position = waitingRoom.position(ticket);
        int retryAfter = AdmissionMath.retryAfterSeconds(position, waitingRoom.admitRatePerSecond());
        String signed = tokens.issue(Kind.TICKET, ticket, now.plus(ticketTtl), subject);
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfter));
        headers.setCacheControl(CacheControl.noStore());
        GatewayErrorCode code = GatewayErrorCode.ADMISSION_QUEUED;
        return GatewayResponses.write(exchange, HttpStatusCode.valueOf(code.httpStatus()), code, code.message(),
                new QueueTicketView(signed, position, retryAfter));
    }

    /** Signature check plus the counters cached by the 1s tick: no Redis call per poll. */
    private Mono<Void> status(ServerWebExchange exchange, String userId) {
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
        if (tokens == null) {
            return GatewayResponses.ok(exchange, new QueueStatusView(0, 0, null, null));
        }
        ServerHttpRequest request = exchange.getRequest();
        String clientIp = request.getHeaders().getFirst(HeaderNames.CLIENT_IP);
        Instant now = clock.instant();
        Claims ticket = tokens.verify(request.getQueryParams().getFirst("ticket"), Kind.TICKET, now, userId, clientIp);
        if (ticket == null) {
            return GatewayResponses.error(exchange, GatewayErrorCode.INVALID_QUEUE_TICKET);
        }
        long position = waitingRoom.position(ticket.number());
        if (position > 0) {
            int retryAfter = AdmissionMath.retryAfterSeconds(position, waitingRoom.admitRatePerSecond());
            return GatewayResponses.ok(exchange, new QueueStatusView(position, retryAfter, null, null));
        }
        Instant expiresAt = now.plus(passTtl);
        // same binding as the ticket, which just verified against this request
        Subject subject = Subject.forBinding(ticket.binding(), userId, clientIp);
        String pass = tokens.issue(Kind.PASS, ticket.number(), expiresAt, subject);
        return GatewayResponses.ok(exchange, new QueueStatusView(0, 0, pass, expiresAt));
    }

    private static boolean isQueueStatus(ServerHttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod())
                && WaitingRoom.QUEUE_STATUS_PATH.equals(request.getPath().pathWithinApplication().value());
    }

    @Override
    public int getOrder() {
        return FilterOrders.ADMISSION;
    }
}
