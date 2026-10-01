package com.bingo789.common.obs;

import com.bingo789.common.core.RetryableException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * LOCAL DEVELOPMENT ONLY ({@code bingo.obs.local-dir}): objects are files under that directory, handed out as
 * {@code <base-url>/<key>?expires=<epoch seconds>&sig=<hex HMAC-SHA256(key + "\n" + expires)>}. Something else serves
 * the files and checks the signature with the same key ({@code deploy/local/public_proxy.py}, behind a tunnel, so a
 * remote worker such as RunPod can fetch them).
 * <p>
 * The base URL is {@code bingo.obs.local-base-url}, or, when that is empty, the content of {@code <local-dir>/.base-url}
 * read on every call: a tunnel whose address changes on every start rewrites that file, no restart needed.
 */
@Slf4j
public class LocalDiskStorage implements ObsStorage {

    static final String BASE_URL_FILE = ".base-url";

    private final Path root;
    private final ObsProperties properties;
    private final byte[] signingKey;

    public LocalDiskStorage(ObsProperties properties) {
        this.properties = properties;
        this.root = Path.of(properties.localDir()).toAbsolutePath().normalize();
        if (properties.localSigningKey() == null || properties.localSigningKey().length() < 32) {
            throw new IllegalStateException("bingo.obs.local-signing-key (>= 32 characters) is required with bingo.obs.local-dir");
        }
        this.signingKey = properties.localSigningKey().getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create " + root, e);
        }
        log.warn("LOCAL DISK object storage in {} - local development only", root);
    }

    @Override
    public StoredObject uploadImage(String prefix, byte[] data) {
        ImageFormat format = ObjectKeys.checkImage(data, properties);
        String key = ObjectKeys.newKey(prefix, format);
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".upload", ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("local upload of {} failed: {}", key, e.toString());
            throw new RetryableException("image storage temporarily unavailable");
        }
        return new StoredObject(key, url(key), format.contentType(), data.length);
    }

    @Override
    public String url(String key) {
        return signedUrl(key, properties.signedUrlTtl());
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        long expires = Instant.now().plus(ttl).getEpochSecond();
        return baseUrl() + "/" + key + "?expires=" + expires + "&sig=" + sign(key, expires);
    }

    @Override
    public boolean exists(String key) {
        return ObjectKeys.validKey(key) != null && Files.isRegularFile(resolve(key));
    }

    /** A bare key, or any URL whose path is the base URL's path followed by the key (the tunnel host may have changed). */
    @Override
    public String keyOf(String urlOrKey) {
        if (urlOrKey == null || urlOrKey.isBlank()) {
            return null;
        }
        String value = urlOrKey.trim();
        if (!value.contains("://")) {
            return ObjectKeys.validKey(value);
        }
        try {
            String path = URI.create(value).getRawPath();
            String basePath = URI.create(baseUrl()).getRawPath();
            String prefix = (basePath == null ? "" : basePath) + "/";
            return path != null && path.startsWith(prefix)
                    ? ObjectKeys.validKey(URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8))
                    : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    String sign(String key, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((key + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String baseUrl() {
        String base = properties.localBaseUrl();
        if (base == null || base.isBlank()) {
            try {
                base = Files.readString(root.resolve(BASE_URL_FILE)).strip();
            } catch (IOException e) {
                throw new RetryableException("local storage has no base URL yet (start deploy/local/tunnel.sh)");
            }
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("key outside the storage root: " + key);
        }
        return file;
    }
}
