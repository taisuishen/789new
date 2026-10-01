package com.bingo789.game.adapter;

import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.config.GameIntegrationProperties.CircuitBreakerSettings;
import com.bingo789.game.config.GameIntegrationProperties.ProviderConfig;
import com.bingo789.game.config.WalletMode;
import com.bingo789.game.provider.ProviderClient;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builders for adapter unit tests: a provider client with the adapter's settings / secrets, and raw callbacks. */
public final class AdapterTests {

    private AdapterTests() {
    }

    public static ProviderConfig config(String operatorId, List<String> currencies, Map<String, String> settings) {
        return new ProviderConfig(true, null, WalletMode.SEAMLESS, "https://provider.example", operatorId, null,
                false, List.of(), Duration.ofSeconds(300), true, currencies, 2, 64, Duration.ofSeconds(1),
                Duration.ofSeconds(5), new CircuitBreakerSettings(50, Duration.ofSeconds(3), Duration.ofSeconds(30)),
                true, Map.of(), settings);
    }

    /** A client whose outbound calls go to https://provider.example (no real HTTP in unit tests). */
    public static ProviderClient client(String providerCode, String operatorId, String secret, Map<String, String> secrets,
                                        Map<String, String> settings) {
        return ProviderClient.unguarded(providerCode, config(operatorId, List.of("PHP"), settings), secret, secrets,
                RestClient.create("https://provider.example"));
    }

    public static CallbackRequest post(String providerCode, String action, String body) {
        return new CallbackRequest(providerCode, action, Map.of(), null, body.getBytes(StandardCharsets.UTF_8),
                "127.0.0.1", Instant.now());
    }

    public static CallbackRequest post(String providerCode, String action, Map<String, String> headers, String body) {
        Map<String, String> lower = new HashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        return new CallbackRequest(providerCode, action, lower, null, body.getBytes(StandardCharsets.UTF_8),
                "127.0.0.1", Instant.now());
    }

    public static CallbackRequest get(String providerCode, String action, String queryString) {
        return new CallbackRequest(providerCode, action, Map.of(), queryString, new byte[0], "127.0.0.1", Instant.now());
    }
}
