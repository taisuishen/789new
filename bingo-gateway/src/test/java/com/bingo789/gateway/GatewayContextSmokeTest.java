package com.bingo789.gateway;

import com.bingo789.gateway.admission.WaitingRoom;
import com.bingo789.gateway.config.BingoGatewayProperties;
import com.bingo789.gateway.filter.AdmissionGlobalFilter;
import com.bingo789.gateway.filter.DegradeGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring check without Nacos or Redis: the gateway must start while Redis is unreachable (revocation listener and
 * waiting-room tasks retry in the background and fail open).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "NACOS_ENABLED=false",
        "GEO_ALLOWED_COUNTRIES=PH",
        "ADMISSION_PASS_SECRET=0123456789abcdef0123456789abcdef",
        "management.server.port=-1"
})
class GatewayContextSmokeTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextWiresTheNewComponents() {
        BingoGatewayProperties properties = context.getBean(BingoGatewayProperties.class);
        assertThat(properties.admission().protectedPaths()).containsExactly("/api/user/login", "/api/lobby/games/*/launch");
        assertThat(properties.admission().maxOnline()).isEqualTo(1_000_000);
        assertThat(context.getBean(AdmissionGlobalFilter.class)).isNotNull();
        assertThat(context.getBean(DegradeGlobalFilter.class)).isNotNull();
        assertThat(context.getBean(WaitingRoom.class).enabled()).isTrue();

        List<Route> routes = context.getBean(RouteLocator.class).getRoutes().collectList().block();
        assertThat(routes).extracting(Route::getId).contains("bingo-waiting-room", "bingo-user", "bingo-wallet");
    }
}
