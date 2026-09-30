package com.bingo789.gateway.filter;

import com.bingo789.common.core.HeaderNames;
import com.bingo789.common.core.trace.TraceContext;
import com.bingo789.gateway.support.GatewayResponses;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/** Accepts a well-formed client trace id (useful for support tickets), otherwise creates one. */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HeaderNames.TRACE_ID);
        String traceId = incoming != null && VALID.matcher(incoming).matches() ? incoming : TraceContext.newTraceId();
        exchange.getAttributes().put(GatewayResponses.TRACE_ID_ATTR, traceId);
        // set (not add) just before commit, replacing the copy echoed by the downstream service
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HeaderNames.TRACE_ID, traceId);
            return Mono.empty();
        });
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HeaderNames.TRACE_ID, traceId))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return FilterOrders.TRACE_ID;
    }
}
