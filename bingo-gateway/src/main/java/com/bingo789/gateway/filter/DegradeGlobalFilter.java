package com.bingo789.gateway.filter;

import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.gateway.support.GatewayResponses;
import com.bingo789.gateway.support.PathRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Degrade switches: requests matching {@code bingo.gateway.degrade.disabled-paths} get HTTP 503 "temporarily
 * disabled" without reaching the upstream (nor Redis: this runs before auth). Changeable at runtime from Nacos:
 * the list is re-bound on every {@link EnvironmentChangeEvent}; an invalid list is rejected and the previous one
 * stays active.
 * <p>
 * Under load ops typically disable read-heavy extras: bet history ({@code /api/bet-records/**}), promotion pages
 * ({@code /api/promotion/**}), leaderboards. Never disable wallet ({@code /api/wallet/**}), withdrawals, or game
 * launch (already admitted players must keep playing; entry is throttled by the waiting room instead).
 */
@Slf4j
@Component
public class DegradeGlobalFilter implements GlobalFilter, Ordered {

    static final String DISABLED_PATHS = "bingo.gateway.degrade.disabled-paths";
    static final String MESSAGE = "temporarily disabled";

    private final Environment environment;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    /** @throws org.springframework.web.util.pattern.PatternParseException at startup on an invalid pattern */
    public DegradeGlobalFilter(Environment environment) {
        this.environment = environment;
        List<String> paths = bind();
        current.set(new Snapshot(paths, PathRules.of(paths)));
        if (!paths.isEmpty()) {
            log.warn("degrade: disabled paths {}", paths);
        }
    }

    /** Cheap enough to re-bind on every change event, whichever keys changed. */
    @EventListener
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        List<String> paths;
        PathRules rules;
        try {
            paths = bind();
            rules = PathRules.of(paths);
        } catch (RuntimeException e) {
            log.error("invalid {}, keeping {}", DISABLED_PATHS, current.get().paths(), e);
            return;
        }
        Snapshot previous = current.getAndSet(new Snapshot(paths, rules));
        if (!previous.paths().equals(paths)) {
            log.warn("degrade: disabled paths changed from {} to {}", previous.paths(), paths);
        }
    }

    private List<String> bind() {
        return List.copyOf(Binder.get(environment)
                .bind(DISABLED_PATHS, Bindable.listOf(String.class))
                .orElse(List.of()));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (current.get().rules().matches(exchange.getRequest())) {
            return GatewayResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, CommonErrorCode.SERVICE_UNAVAILABLE, MESSAGE);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FilterOrders.DEGRADE;
    }

    private record Snapshot(List<String> paths, PathRules rules) {
    }
}
