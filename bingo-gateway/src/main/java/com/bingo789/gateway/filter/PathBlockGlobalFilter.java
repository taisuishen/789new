package com.bingo789.gateway.filter;

import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.gateway.config.BingoGatewayProperties;
import com.bingo789.gateway.support.GatewayResponses;
import com.bingo789.gateway.support.PathRules;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Internal RPC (/internal/**) and provider callbacks (/callback/**) are never served by the player gateway:
 * provider callbacks use a physically separate ingress and cluster. Unrouted paths already get 404; this filter
 * also rejects paths that match a public route here but that the downstream servlet container would normalize
 * into a blocked path (dot segments, encoded separators, matrix parameters), e.g. /api/user/..;/internal/...
 */
@Component
public class PathBlockGlobalFilter implements GlobalFilter, Ordered {

    private final PathRules blocked;

    public PathBlockGlobalFilter(BingoGatewayProperties properties) {
        this.blocked = PathRules.of(properties.blockedPaths());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (isAmbiguous(request) || blocked.matches(request)) {
            return GatewayResponses.error(exchange, CommonErrorCode.NOT_FOUND);
        }
        return chain.filter(exchange);
    }

    private static boolean isAmbiguous(ServerHttpRequest request) {
        String raw = request.getURI().getRawPath();
        if (raw == null || raw.indexOf(';') >= 0 || raw.indexOf('\\') >= 0 || raw.contains("//")) {
            return true;
        }
        for (PathContainer.Element element : request.getPath().pathWithinApplication().elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                String value = segment.valueToMatch(); // decoded, so %2e%2e and %2f are caught too
                if (value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return FilterOrders.PATH_BLOCK;
    }
}
