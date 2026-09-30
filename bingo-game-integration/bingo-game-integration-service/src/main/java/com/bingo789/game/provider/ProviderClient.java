package com.bingo789.game.provider;

import com.bingo789.game.config.GameIntegrationProperties.ProviderConfig;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * Outbound access to one provider. Each provider gets its own HTTP client (own connection pool),
 * bulkhead and circuit breaker, so a slow or failing provider cannot exhaust shared resources
 * (bulkhead isolation). Adapters never call {@link #execute}; the framework wraps every outbound adapter call.
 */
public final class ProviderClient {

    private final String providerCode;
    private final ProviderConfig config;
    private final String secret;
    private final RestClient rest;
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;

    ProviderClient(String providerCode, ProviderConfig config, String secret, RestClient rest,
                   Bulkhead bulkhead, CircuitBreaker circuitBreaker) {
        this.providerCode = providerCode;
        this.config = config;
        this.secret = secret;
        this.rest = rest;
        this.bulkhead = bulkhead;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Runs an outbound call inside the provider's bulkhead and circuit breaker. Fails fast with
     * {@code BulkheadFullException} / {@code CallNotPermittedException} instead of queueing.
     */
    public <T> T execute(Supplier<T> call) {
        return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    public String providerCode() {
        return providerCode;
    }

    public ProviderConfig config() {
        return config;
    }

    /** Resolved signing secret (never log it). */
    public String secret() {
        return secret;
    }

    public RestClient rest() {
        return rest;
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }
}
