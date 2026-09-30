package com.bingo789.gateway.admission;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.gateway.support.Periodic;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import reactor.core.Disposable;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class AdmissionConfig {

    @Bean
    public Clock clock() {
        return Clock.system(BingoTime.ZONE);
    }

    /**
     * Route for the queue status endpoint. It has no upstream: AdmissionGlobalFilter answers it. The route only
     * exists so that the global filters (trace id, path block, geo-fence, degrade, auth) run for it like for any
     * player API; a WebFlux handler at the same path would bypass them.
     */
    @Bean
    public RouteLocator waitingRoomRoute(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("bingo-waiting-room", r -> r.path(WaitingRoom.QUEUE_STATUS_PATH)
                        .and().method(HttpMethod.GET)
                        .uri("no://op"))
                .build();
    }

    /** Background work: online flush (10s), estimate (5s) and, while the waiting room is enabled, the 1s tick. */
    @Bean
    public SmartLifecycle admissionTasks(OnlineTracker online, WaitingRoom waitingRoom) {
        return new SmartLifecycle() {

            private volatile List<Disposable> running = List.of();

            @Override
            public void start() {
                List<Disposable> tasks = new ArrayList<>();
                tasks.add(Periodic.every("online flush", OnlineTracker.FLUSH_INTERVAL,
                        OnlineTracker.FLUSH_INTERVAL, online::flush));
                tasks.add(Periodic.every("online estimate", OnlineTracker.ESTIMATE_INTERVAL,
                        OnlineTracker.ESTIMATE_INTERVAL, online::refreshEstimate));
                if (waitingRoom.enabled()) {
                    tasks.add(Periodic.every("waiting-room tick", Duration.ofSeconds(1),
                            Duration.ofSeconds(1), waitingRoom::tick));
                }
                running = List.copyOf(tasks);
            }

            @Override
            public void stop() {
                running.forEach(Disposable::dispose);
                running = List.of();
            }

            @Override
            public boolean isRunning() {
                return !running.isEmpty();
            }
        };
    }
}
