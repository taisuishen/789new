package com.bingo789.gateway.filter;

import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.gateway.config.BingoGatewayProperties;
import com.bingo789.gateway.support.GatewayResponses;
import com.bingo789.gateway.support.PathRules;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Licence requirement: service is offered only in licensed jurisdictions. Huawei WAF enforces it (geolocation access
 * control rule allowing only the licensed countries) but cannot forward the client's country, so its header
 * forwarding adds this header with a static value (X-Country-Code: PH). The header therefore proves the request
 * passed WAF; the player ELB must accept only WAF back-to-source traffic. Fails closed: a request without the
 * header is rejected (HTTP 451).
 */
@Component
public class GeoFenceGlobalFilter implements GlobalFilter, Ordered {

    private final boolean enabled;
    private final String countryHeader;
    private final Set<String> allowedCountries;
    private final PathRules bypass;

    public GeoFenceGlobalFilter(BingoGatewayProperties properties) {
        BingoGatewayProperties.Geo geo = properties.geo();
        this.enabled = geo.enabled();
        this.countryHeader = geo.countryHeader();
        this.allowedCountries = geo.allowedCountries().stream()
                .map(country -> country.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.bypass = PathRules.of(geo.bypassPaths());
        if (enabled && allowedCountries.isEmpty()) {
            throw new IllegalStateException("bingo.gateway.geo.allowed-countries must not be empty while geo-fencing is enabled");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled || bypass.matches(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        String country = exchange.getRequest().getHeaders().getFirst(countryHeader);
        if (country == null || !allowedCountries.contains(country.trim().toUpperCase(Locale.ROOT))) {
            // TODO: count rejections per country (Micrometer) for the compliance dashboard.
            return GatewayResponses.error(exchange, CommonErrorCode.REGION_NOT_ALLOWED);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FilterOrders.GEO_FENCE;
    }
}
