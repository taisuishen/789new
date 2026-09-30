package com.bingo789.common.core.secret;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves secret references kept in configuration (Nacos, config tables). {@code dew:csms/<name>} is read from the
 * file the CCE DEW/CSMS secrets add-on mounts under {@code mountPath}, falling back to an environment variable named
 * after the secret (upper case, '-' and '.' replaced by '_'). Any other value is returned as-is (local development
 * only: production configuration must hold references, never the secret itself).
 */
public final class SecretRefs {

    public static final String DEW_PREFIX = "dew:csms/";
    /** Default mount path of the CCE DEW/CSMS add-on (override with BINGO_SECRETS_PATH). */
    public static final String DEFAULT_MOUNT_PATH = "/mnt/csms";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path mountPath;

    public SecretRefs(String mountPath) {
        this.mountPath = Path.of(mountPath == null || mountPath.isBlank() ? DEFAULT_MOUNT_PATH : mountPath);
    }

    public static boolean isReference(String value) {
        return value != null && value.startsWith(DEW_PREFIX);
    }

    public String resolve(String value) {
        if (!isReference(value)) {
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
