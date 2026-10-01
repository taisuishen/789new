package com.bingo789.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Bound from {@code bingo.*}. Onboarding a provider = one adapter bean (or reuse of an existing protocol
 * via {@link ProviderConfig#adapter()}) plus one entry under {@code bingo.providers} in Nacos.
 */
@ConfigurationProperties("bingo")
public record GameIntegrationProperties(
        @DefaultValue Map<String, ProviderConfig> providers,
        @DefaultValue CallbackSettings callback,
        @DefaultValue SecretSettings secrets) {

    /**
     * @param adapter            adapter name implementing this provider's protocol; defaults to the provider code.
     *                           A second merchant account of the same provider (e.g. another line) is another provider
     *                           code with the same adapter: it has its own callback URL, keys and settings
     * @param secret             signing secret, either plain (local dev only) or a {@code dew:csms/<name>} reference
     * @param secrets            further named secrets of the protocol (AES key / IV, API tokens ...), same format as
     *                           {@code secret}; read with ProviderClient#secret(String)
     * @param settings           non-secret protocol settings (agent / host ids, lobby code, extra endpoints, language
     *                           ...), read with ProviderClient#setting(String); the adapter documents its keys
     * @param ipWhitelist        CIDR list of provider callback source addresses
     * @param balanceScale       decimals shown to the provider; balances are rounded DOWN, never up
     * @param maxConcurrency     bulkhead size for outbound calls to this provider
     */
    public record ProviderConfig(
            @DefaultValue("true") boolean enabled,
            String adapter,
            @DefaultValue("SEAMLESS") WalletMode walletMode,
            String baseUrl,
            String operatorId,
            String secret,
            @DefaultValue("true") boolean ipWhitelistEnabled,
            @DefaultValue List<String> ipWhitelist,
            @DefaultValue("300s") Duration timestampTolerance,
            @DefaultValue("true") boolean requireBetForPayout,
            @DefaultValue List<String> currencies,
            @DefaultValue("2") int balanceScale,
            @DefaultValue("64") int maxConcurrency,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            @DefaultValue CircuitBreakerSettings circuitBreaker,
            @DefaultValue("true") boolean autoTransferOnLaunch,
            @DefaultValue Map<String, String> secrets,
            @DefaultValue Map<String, String> settings) {
    }

    public record CircuitBreakerSettings(
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("3s") Duration slowCallDuration,
            @DefaultValue("30s") Duration waitInOpenState) {
    }

    public record CallbackSettings(
            @DefaultValue("1") int trustedProxyHops,
            @DefaultValue RateLimit rateLimit) {
    }

    public record RateLimit(
            @DefaultValue("5000") double perProviderQps,
            @DefaultValue("30") double perPlayerQps) {
    }

    public record SecretSettings(@DefaultValue("/mnt/csms") String mountPath) {
    }
}
