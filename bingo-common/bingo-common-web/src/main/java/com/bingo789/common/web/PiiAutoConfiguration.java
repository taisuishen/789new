package com.bingo789.common.web;

import com.bingo789.common.core.crypto.PiiCipher;
import com.bingo789.common.core.secret.SecretRefs;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link PiiCipher} for the services that store personal data (bingo-user, bingo-kyc), enabled by setting
 * {@code bingo.pii.current-key}. Every key is a {@code dew:csms/<name>} reference (SecretRefs: the DEW mount, else
 * the environment variable of the same name); a missing key stops the service at startup.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "bingo.pii", name = "current-key")
@EnableConfigurationProperties(PiiAutoConfiguration.PiiProperties.class)
public class PiiAutoConfiguration {

    @Bean
    PiiCipher piiCipher(PiiProperties properties) {
        SecretRefs secrets = new SecretRefs(properties.secretsPath());
        Map<String, String> keys = new LinkedHashMap<>();
        properties.keys().forEach((id, ref) -> keys.put(id, secrets.resolve(ref)));
        return new PiiCipher(keys, properties.currentKey(), secrets.resolve(properties.indexKey()));
    }

    /**
     * @param keys        key id -> reference to Base64 of 32 random bytes, e.g. {@code k1: dew:csms/bingo-pii-key-k1}
     * @param currentKey  key id used for new values
     * @param indexKey    reference to the blind-index HMAC key (>= 32 characters, never rotated)
     * @param secretsPath DEW/CSMS mount
     */
    @ConfigurationProperties("bingo.pii")
    public record PiiProperties(Map<String, String> keys, String currentKey, String indexKey, String secretsPath) {

        public PiiProperties {
            keys = keys == null ? Map.of() : Map.copyOf(keys);
        }
    }
}
