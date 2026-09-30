package com.bingo789.game.security;

import com.bingo789.game.config.GameIntegrationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves provider secrets. {@code dew:csms/<name>} references are read from the file the CCE DEW/CSMS
 * secrets add-on mounts under {@code bingo.secrets.mount-path}, falling back to an environment variable
 * named after the secret (upper case, '-' replaced by '_'). Any other value is used as-is (local dev only).
 */
@Component
public class SecretResolver {

    private static final String DEW_PREFIX = "dew:csms/";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path mountPath;

    public SecretResolver(GameIntegrationProperties properties) {
        this.mountPath = Path.of(properties.secrets().mountPath());
    }

    public String resolve(String value) {
        if (value == null || !value.startsWith(DEW_PREFIX)) {
            return value;
        }
        String name = value.substring(DEW_PREFIX.length());
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid secret name: " + name);
        }
        Path file = mountPath.resolve(name);
        if (Files.isReadable(file)) {
            try {
                return Files.readString(file).strip();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read secret " + name, e);
            }
        }
        String env = System.getenv(name.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_'));
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException("secret " + name + " is neither mounted at " + file + " nor set as an environment variable");
    }
}
