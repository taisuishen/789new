package com.bingo789.game.provider;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.game.adapter.ProviderAdapter;
import com.bingo789.game.config.GameIntegrationProperties;
import com.bingo789.game.config.GameIntegrationProperties.ProviderConfig;
import com.bingo789.game.security.CidrMatcher;
import com.bingo789.game.security.SecretResolver;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds one {@link ProviderRuntime} per enabled provider at startup.
 * TODO: rebuild runtimes on Nacos config refresh (EnvironmentChangeEvent) so providers can be added without a restart.
 */
@Slf4j
@Component
public class ProviderRegistry {

    private final Map<String, ProviderRuntime> runtimes;

    public ProviderRegistry(GameIntegrationProperties properties, List<ProviderAdapter> adapters,
                            SecretResolver secretResolver, ProviderHealthNotifier healthNotifier) {
        Map<String, ProviderAdapter> adaptersByName = adapters.stream()
                .collect(Collectors.toMap(ProviderAdapter::name, Function.identity()));
        Map<String, ProviderRuntime> built = new HashMap<>();
        properties.providers().forEach((code, config) -> {
            if (!config.enabled()) {
                return;
            }
            String adapterName = config.adapter() == null || config.adapter().isBlank() ? code : config.adapter();
            ProviderAdapter adapter = adaptersByName.get(adapterName);
            if (adapter == null) {
                log.error("provider {} is enabled but no adapter named {} exists; it will reject all traffic", code, adapterName);
                return;
            }
            if (config.ipWhitelistEnabled() && config.ipWhitelist().isEmpty()) {
                log.warn("provider {} has an empty IP whitelist: all callbacks will be refused", code);
            }
            Map<String, String> secrets = new HashMap<>();
            config.secrets().forEach((name, ref) -> secrets.put(name, secretResolver.resolve(ref)));
            ProviderClient client = new ProviderClient(code, config, secretResolver.resolve(config.secret()), secrets,
                    restClient(config), bulkhead(code, config), circuitBreaker(code, config, healthNotifier));
            built.put(code, new ProviderRuntime(code, config, adapter, client, CidrMatcher.of(config.ipWhitelist())));
            log.info("provider {} ready: adapter={}, walletMode={}", code, adapterName, config.walletMode());
        });
        this.runtimes = Map.copyOf(built);
    }

    public Optional<ProviderRuntime> find(String providerCode) {
        return Optional.ofNullable(runtimes.get(providerCode));
    }

    public ProviderRuntime require(String providerCode) {
        return find(providerCode).orElseThrow(() ->
                new BizException(CommonErrorCode.NOT_FOUND, "provider " + providerCode + " is not configured or disabled"));
    }

    public List<ProviderRuntime> all() {
        return List.copyOf(runtimes.values());
    }

    private static RestClient restClient(ProviderConfig config) {
        // one JDK HttpClient per provider = one connection pool per provider
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.readTimeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (config.baseUrl() != null) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }

    private static Bulkhead bulkhead(String code, ProviderConfig config) {
        return Bulkhead.of("provider-" + code, BulkheadConfig.custom()
                .maxConcurrentCalls(config.maxConcurrency())
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    private static CircuitBreaker circuitBreaker(String code, ProviderConfig config, ProviderHealthNotifier notifier) {
        CircuitBreaker breaker = CircuitBreaker.of("provider-" + code, CircuitBreakerConfig.custom()
                .failureRateThreshold(config.circuitBreaker().failureRateThreshold())
                .slowCallDurationThreshold(config.circuitBreaker().slowCallDuration())
                .slowCallRateThreshold(80)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(30)
                .minimumNumberOfCalls(20)
                .waitDurationInOpenState(config.circuitBreaker().waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(5)
                .build());
        breaker.getEventPublisher().onStateTransition(event ->
                notifier.onStateTransition(code, event.getStateTransition().getToState()));
        return breaker;
    }
}
