package com.bingo789.game.provider;

import com.bingo789.game.config.GameIntegrationProperties.ProviderConfig;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.web.client.RestClient;

import java.util.Map;
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
    private final Map<String, String> secrets;
    private final RestClient rest;
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;

    ProviderClient(String providerCode, ProviderConfig config, String secret, Map<String, String> secrets, RestClient rest,
                   Bulkhead bulkhead, CircuitBreaker circuitBreaker) {
        this.providerCode = providerCode;
        this.config = config;
        this.secret = secret;
        this.secrets = Map.copyOf(secrets);
        this.rest = rest;
        this.bulkhead = bulkhead;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * A client without bulkhead limits or circuit breaking, for adapter tests and tools; the service builds its
     * clients in ProviderRegistry.
     */
    public static ProviderClient unguarded(String providerCode, ProviderConfig config, String secret,
                                           Map<String, String> secrets, RestClient rest) {
        return new ProviderClient(providerCode, config, secret, secrets, rest,
                Bulkhead.of("unguarded-" + providerCode, io.github.resilience4j.bulkhead.BulkheadConfig.custom()
                        .maxConcurrentCalls(Integer.MAX_VALUE).build()),
                CircuitBreaker.ofDefaults("unguarded-" + providerCode));
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

    /** A resolved named secret of {@code bingo.providers.<code>.secrets} (never log it). */
    public String secret(String name) {
        String value = secrets.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("provider " + providerCode + ": secrets." + name + " is not configured");
        }
        return value;
    }

    /** A required setting of {@code bingo.providers.<code>.settings}. */
    public String setting(String name) {
        String value = config.settings().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("provider " + providerCode + ": settings." + name + " is not configured");
        }
        return value;
    }

    public String setting(String name, String defaultValue) {
        String value = config.settings().get(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public RestClient rest() {
        return rest;
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }
}
