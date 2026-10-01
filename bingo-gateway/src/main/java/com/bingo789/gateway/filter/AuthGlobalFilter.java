package com.bingo789.gateway.filter;

import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.HeaderNames;
import com.bingo789.common.core.session.SessionKeys;
import com.bingo789.gateway.admission.WaitingRoom;
import com.bingo789.gateway.config.BingoGatewayProperties;
import com.bingo789.gateway.support.GatewayResponses;
import com.bingo789.gateway.support.PathRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the opaque session token (written by bingo-user under {@link SessionKeys#session}) and asserts the
 * player's identity to downstream services through X-User-Id / X-Session-Id. Client-supplied copies of the
 * identity headers are always dropped, on public paths too, because downstream services trust them blindly.
 * <p>
 * Every request reads the session from Redis (one GET), so a logout or a self-exclusion takes effect on the very next
 * request. Public paths pass without a token; when a valid token is present anyway, the identity is still
 * forwarded. If Redis is unavailable, protected paths get 503 (never let an unverified request through).
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{20,128}");
    private static final Pattern IP_FORMAT = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern DEVICE_ID_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern USER_ID_FORMAT = Pattern.compile("[0-9]{1,19}");

    private final ReactiveStringRedisTemplate redis;
    private final PathRules publicPaths;
    private final int trustedHops;

    public AuthGlobalFilter(ReactiveStringRedisTemplate redis, BingoGatewayProperties properties) {
        this.redis = redis;
        List<String> paths = new ArrayList<>(properties.auth().publicPaths());
        // Always public, whatever the configured list says: it is polled by players queued for login.
        paths.add("GET " + WaitingRoom.QUEUE_STATUS_PATH);
        this.publicPaths = PathRules.of(paths);
        this.trustedHops = properties.clientIp().trustedHops();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean publicPath = publicPaths.matches(request);
        String clientIp = clientIp(request);
        String deviceId = deviceId(request);
        String token = bearerToken(request);

        if (token == null) {
            return publicPath
                    ? chain.filter(withIdentity(exchange, null, null, clientIp, deviceId))
                    : unauthorized(exchange);
        }
        String sessionId = SessionKeys.sessionId(token);
        return lookup(token).flatMap(session -> {
            if (session.userId() != null) {
                return chain.filter(withIdentity(exchange, session.userId(), sessionId, clientIp, deviceId));
            }
            if (publicPath) {
                return chain.filter(withIdentity(exchange, null, null, clientIp, deviceId));
            }
            return session.failed()
                    ? GatewayResponses.error(exchange, CommonErrorCode.SERVICE_UNAVAILABLE)
                    : unauthorized(exchange);
        });
    }

    /** Errors are mapped here, before flatMap, so a downstream failure is never retried through the chain again. */
    private Mono<SessionLookup> lookup(String token) {
        return redis.opsForValue().get(SessionKeys.session(token))
                .filter(value -> USER_ID_FORMAT.matcher(value).matches())
                .map(SessionLookup::found)
                .defaultIfEmpty(SessionLookup.MISSING)
                .onErrorResume(e -> {
                    log.warn("session lookup failed", e);
                    return Mono.just(SessionLookup.FAILED);
                });
    }

    private static ServerWebExchange withIdentity(ServerWebExchange exchange, String userId, String sessionId,
                                                  String clientIp, String deviceId) {
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(HeaderNames.USER_ID);
            headers.remove(HeaderNames.SESSION_ID);
            headers.remove(HeaderNames.DEVICE_ID);
            headers.set(HeaderNames.CLIENT_IP, clientIp);
            if (userId != null) {
                headers.set(HeaderNames.USER_ID, userId);
                headers.set(HeaderNames.SESSION_ID, sessionId);
            }
            if (deviceId != null) {
                headers.set(HeaderNames.DEVICE_ID, deviceId);
            }
        }).build();
        return exchange.mutate().request(request).build();
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return GatewayResponses.error(exchange, CommonErrorCode.UNAUTHORIZED);
    }

    private static String bearerToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return TOKEN_FORMAT.matcher(token).matches() ? token : null;
    }

    /**
     * The entry our outermost proxy appended (trustedHops from the right). Entries further left are whatever the client
     * sent; trusting them would let a client choose its IP for rate limits, the waiting room and risk's multi-account
     * detection.
     */
    private String clientIp(ServerHttpRequest request) {
        List<String> forwarded = request.getHeaders().get(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isEmpty()) {
            String[] hops = String.join(",", forwarded).split(",");
            int index = trustedHops <= 0 ? 0 : Math.max(0, hops.length - trustedHops);
            String candidate = hops[index].trim();
            if (IP_FORMAT.matcher(candidate).matches()) {
                return candidate;
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    /** Client-supplied and informational only; passed through when well-formed. */
    private static String deviceId(ServerHttpRequest request) {
        String deviceId = request.getHeaders().getFirst(HeaderNames.DEVICE_ID);
        return deviceId != null && DEVICE_ID_FORMAT.matcher(deviceId).matches() ? deviceId : null;
    }

    @Override
    public int getOrder() {
        return FilterOrders.AUTH;
    }

    private record SessionLookup(String userId, boolean failed) {

        static final SessionLookup MISSING = new SessionLookup(null, false);
        static final SessionLookup FAILED = new SessionLookup(null, true);

        static SessionLookup found(String userId) {
            return new SessionLookup(userId, false);
        }
    }
}
