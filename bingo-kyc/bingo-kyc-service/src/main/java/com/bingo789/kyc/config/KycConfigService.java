package com.bingo789.kyc.config;

import com.bingo789.common.core.secret.SecretRefs;
import com.bingo789.kyc.domain.ConfigEntry;
import com.bingo789.kyc.mapper.ConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Settings from the {@code config} table (seeded by deploy/sql/11_kyc.sql), cached and re-read every 30 s so a row
 * edit takes effect without a restart. Secrets are {@code dew:csms/<name>} references resolved on use.
 * A value still holding a {@code __PLACEHOLDER__} counts as missing.
 */
@Slf4j
@Service
public class KycConfigService {

    /** Seed values still to be replaced, e.g. __RUNPOD_ENDPOINT_ID__. */
    private static final Pattern PLACEHOLDER = Pattern.compile("__[A-Z0-9_]+__");

    private final ConfigMapper mapper;
    private final SecretRefs secrets;
    private volatile Map<String, String> values;

    public KycConfigService(ConfigMapper mapper, @Value("${bingo.secrets.mount-path:/mnt/csms}") String secretsPath) {
        this.mapper = mapper;
        this.secrets = new SecretRefs(secretsPath);
    }

    @Scheduled(fixedDelayString = "${bingo.kyc.config-refresh:30s}")
    public void refresh() {
        try {
            reload();
        } catch (RuntimeException e) {
            log.warn("config table reload failed, keeping the previous values: {}", e.toString());
        }
    }

    public String runPodApiBaseUrl() {
        return require("RunPodApiBaseUrl");
    }

    public String runPodEndpointId() {
        return require("RunPodEndpointId");
    }

    public String runPodApiKey() {
        return secrets.resolve(require("RunPodApiKey"));
    }

    /**
     * Webhook URL including the shared token RunPod echoes back to us. Null when RunPodWebhookUrl is left empty:
     * results then come from kycResultPollJob only (set KycPollAfterSeconds low, e.g. 5).
     */
    public String runPodWebhookUrl() {
        String url = get("RunPodWebhookUrl");
        if (url == null) {
            return null;
        }
        return url + (url.contains("?") ? "&" : "?") + "token=" + runPodWebhookToken();
    }

    public String runPodWebhookToken() {
        return secrets.resolve(require("RunPodWebhookToken"));
    }

    public Duration runPodHttpTimeout() {
        return Duration.ofMillis(number("RunPodHttpTimeoutMs", 5_000));
    }

    public Duration timeout() {
        return Duration.ofSeconds(number("KycTimeoutSeconds", 300));
    }

    public int maxSubmitAttempts() {
        return (int) number("KycMaxSubmitAttempts", 10);
    }

    public Duration retryBaseDelay() {
        return Duration.ofSeconds(number("KycRetryBaseDelaySeconds", 5));
    }

    public Duration pollAfter() {
        return Duration.ofSeconds(number("KycPollAfterSeconds", 30));
    }

    public Duration imageUrlTtl() {
        return Duration.ofSeconds(number("KycImageUrlTtlSeconds", 900));
    }

    public boolean enableOcr() {
        return !"false".equalsIgnoreCase(get("KycEnableOcr"));
    }

    public int maxSubmissionsPerDay() {
        return (int) number("KycMaxSubmissionsPerDay", 5);
    }

    /** Null when the facecmp pod is not configured (the fallback worker is used directly). */
    public String faceCompareUrl() {
        return get("FaceCompareUrl");
    }

    public Duration faceCompareTimeout() {
        return Duration.ofMillis(number("FaceCompareTimeoutMs", 5_000));
    }

    public Duration faceCompareFallbackTimeout() {
        return Duration.ofMillis(number("FaceCompareFallbackTimeoutMs", 30_000));
    }

    /** @return the value, or null when missing / still a placeholder */
    public String get(String name) {
        Map<String, String> current = values;
        if (current == null) {
            current = reload();
        }
        String value = current.get(name);
        if (value == null || value.isBlank() || PLACEHOLDER.matcher(value).find()) {
            return null;
        }
        return value.trim();
    }

    private String require(String name) {
        String value = get(name);
        if (value == null) {
            throw new IllegalStateException("config " + name + " is not set (table bingo_kyc.config)");
        }
        return value;
    }

    private long number(String name, long fallback) {
        String value = get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("config {}={} is not a number, using {}", name, value, fallback);
            return fallback;
        }
    }

    private Map<String, String> reload() {
        Map<String, String> loaded = new HashMap<>();
        for (ConfigEntry entry : mapper.selectAll()) {
            loaded.put(entry.getCfgName(), entry.getCfgValue());
        }
        Map<String, String> copy = Map.copyOf(loaded);
        values = copy;
        return copy;
    }
}
