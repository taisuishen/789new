package com.bingo789.game.provider;

import com.bingo789.lobby.api.LobbyClient;
import com.bingo789.lobby.api.dto.ProviderStatusCommand;
import com.bingo789.lobby.api.enums.ProviderStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * When a provider's circuit opens, the lobby marks it AUTO_MAINTENANCE so players stop launching its games;
 * when it closes again the provider comes back automatically. Operator-set maintenance is never touched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderHealthNotifier {

    private final LobbyClient lobbyClient;

    void onStateTransition(String providerCode, CircuitBreaker.State toState) {
        ProviderStatus status = switch (toState) {
            case OPEN, FORCED_OPEN -> ProviderStatus.AUTO_MAINTENANCE;
            case CLOSED -> ProviderStatus.ACTIVE;
            default -> null;
        };
        if (status == null) {
            return;
        }
        log.warn("provider {} circuit breaker -> {}, lobby status -> {}", providerCode, toState, status);
        // never block the circuit breaker's event thread on a remote call
        Thread.ofVirtual().name("provider-health-" + providerCode).start(() -> {
            try {
                lobbyClient.updateProviderStatus(new ProviderStatusCommand(providerCode, status, "circuit breaker " + toState));
            } catch (Exception e) {
                log.warn("failed to update lobby status of provider {}", providerCode, e);
            }
        });
    }
}
