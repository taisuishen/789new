package com.bingo789.gateway.filter;

import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.Result;
import com.bingo789.common.core.json.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DegradeGlobalFilterTest {

    private final MockEnvironment environment = new MockEnvironment();

    @Test
    void nothingIsDisabledByDefault() {
        DegradeGlobalFilter filter = new DegradeGlobalFilter(environment);

        assertThat(passes(filter, MockServerHttpRequest.get("/api/bet-records/mine"))).isTrue();
    }

    @Test
    void matchingPathsGet503() {
        environment.setProperty(DegradeGlobalFilter.DISABLED_PATHS + "[0]", "/api/bet-records/**");
        environment.setProperty(DegradeGlobalFilter.DISABLED_PATHS + "[1]", "GET /api/promotion/leaderboard/*");
        DegradeGlobalFilter filter = new DegradeGlobalFilter(environment);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/bet-records/mine"));
        filter.filter(exchange, e -> Mono.error(new AssertionError("must not be forwarded"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Result<Void> body = JsonUtils.fromJson(exchange.getResponse().getBodyAsString().block(), new TypeReference<Result<Void>>() {
        });
        assertThat(body.code()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE.code());
        assertThat(body.message()).isEqualTo("temporarily disabled");

        assertThat(passes(filter, MockServerHttpRequest.get("/api/promotion/leaderboard/weekly"))).isFalse();
        assertThat(passes(filter, MockServerHttpRequest.post("/api/promotion/leaderboard/weekly"))).isTrue();
        assertThat(passes(filter, MockServerHttpRequest.get("/api/wallet/balance"))).isTrue();
    }

    @Test
    void listIsReboundOnEnvironmentChange() {
        DegradeGlobalFilter filter = new DegradeGlobalFilter(environment);

        environment.setProperty(DegradeGlobalFilter.DISABLED_PATHS, "/api/promotion/**,/api/bet-records/**");
        filter.onEnvironmentChange(new EnvironmentChangeEvent(Set.of(DegradeGlobalFilter.DISABLED_PATHS)));
        assertThat(passes(filter, MockServerHttpRequest.get("/api/promotion/list"))).isFalse();
        assertThat(passes(filter, MockServerHttpRequest.get("/api/bet-records/mine"))).isFalse();

        environment.setProperty(DegradeGlobalFilter.DISABLED_PATHS, "");
        filter.onEnvironmentChange(new EnvironmentChangeEvent(Set.of(DegradeGlobalFilter.DISABLED_PATHS)));
        assertThat(passes(filter, MockServerHttpRequest.get("/api/promotion/list"))).isTrue();
    }

    @Test
    void invalidChangeKeepsThePreviousList() {
        environment.setProperty(DegradeGlobalFilter.DISABLED_PATHS, "/api/promotion/**");
        DegradeGlobalFilter filter = new DegradeGlobalFilter(environment);

        environment.setProperty(DegradeGlobalFilter.DISABLED_PATHS, "/api/{broken");
        filter.onEnvironmentChange(new EnvironmentChangeEvent(Set.of(DegradeGlobalFilter.DISABLED_PATHS)));

        assertThat(passes(filter, MockServerHttpRequest.get("/api/promotion/list"))).isFalse();
    }

    @Test
    void invalidListFailsAtStartup() {
        environment.setProperty(DegradeGlobalFilter.DISABLED_PATHS, "/api/{broken");

        assertThatThrownBy(() -> new DegradeGlobalFilter(environment)).isInstanceOf(RuntimeException.class);
    }

    private static boolean passes(DegradeGlobalFilter filter, MockServerHttpRequest.BaseBuilder<?> request) {
        AtomicBoolean forwarded = new AtomicBoolean();
        GatewayFilterChain chain = exchange -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(MockServerWebExchange.from(request), chain).block();
        return forwarded.get();
    }
}
