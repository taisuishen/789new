package com.bingo789.common.obs;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.RetryableException;
import com.bingo789.common.core.time.BingoTime;
import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.HttpMethodEnum;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.TemporarySignatureRequest;
import com.obs.services.model.TemporarySignatureResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Object storage on Huawei OBS. Objects are addressed by key ({@code <prefix>/<yyyyMMdd>/<uuid>.<ext>}); the key is
 * what callers persist. URLs are derived on demand: the public URL for a public bucket behind a custom domain, a
 * signed expiring URL for a private bucket (the default for identity documents).
 */
@Slf4j
public class ObsStorage {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    /** Keys we generate: path segments of letters, digits, '-', '_', '.'. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,1000}");

    private final ObsClient client;
    private final ObsProperties properties;
    private final String bucketHost;

    public ObsStorage(ObsClient client, ObsProperties properties) {
        this.client = client;
        this.properties = properties;
        String endpointHost = URI.create(properties.endpoint().contains("://") ? properties.endpoint()
                : "https://" + properties.endpoint()).getHost();
        this.bucketHost = properties.bucket() + "." + endpointHost;
    }

    /**
     * Stores an image under {@code prefix} after checking its real format (first bytes) and size.
     *
     * @param prefix e.g. "kyc/123456": the caller's namespace, later checked with {@link #isUnder}
     * @throws BizException not an accepted image, or too large (400)
     * @throws RetryableException OBS unavailable (503)
     */
    public StoredObject uploadImage(String prefix, byte[] data) {
        BizException.check(data != null && data.length > 0, CommonErrorCode.BAD_REQUEST, "empty file");
        BizException.check(data.length <= properties.maxImageSize().toBytes(), CommonErrorCode.BAD_REQUEST,
                "image larger than " + properties.maxImageSize().toMegabytes() + "MB");
        ImageFormat format = ImageFormat.detect(data);
        BizException.check(format != null, CommonErrorCode.BAD_REQUEST, "only JPEG, PNG or WebP images are accepted");

        String key = normalizePrefix(prefix) + "/" + LocalDate.now(BingoTime.ZONE).format(DAY) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + format.extension();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(format.contentType());
        metadata.setContentLength((long) data.length);
        PutObjectRequest request = new PutObjectRequest(properties.bucket(), key, new ByteArrayInputStream(data));
        request.setMetadata(metadata);
        try {
            client.putObject(request);
        } catch (ObsException e) {
            log.warn("OBS upload of {} failed: {} {}", key, e.getResponseCode(), e.getErrorCode());
            throw new RetryableException("image storage temporarily unavailable");
        }
        return new StoredObject(key, url(key), format.contentType(), data.length);
    }

    /** Address a client (or a worker such as RunPod) can fetch now. */
    public String url(String key) {
        return properties.privateBucket() ? signedUrl(key, properties.signedUrlTtl()) : publicUrl(key);
    }

    public String signedUrl(String key, Duration ttl) {
        TemporarySignatureRequest request = new TemporarySignatureRequest(HttpMethodEnum.GET, ttl.toSeconds());
        request.setBucketName(properties.bucket());
        request.setObjectKey(key);
        TemporarySignatureResponse response = client.createTemporarySignature(request);
        return response.getSignedUrl();
    }

    public boolean exists(String key) {
        try {
            return client.doesObjectExist(properties.bucket(), key);
        } catch (ObsException e) {
            throw new RetryableException("image storage temporarily unavailable");
        }
    }

    /**
     * Maps what a client sent back (a URL this class handed out, signed or public, or a bare key) to the object key.
     *
     * @return the key, or null when the value does not point into our bucket
     */
    public String keyOf(String urlOrKey) {
        if (urlOrKey == null || urlOrKey.isBlank()) {
            return null;
        }
        String value = urlOrKey.trim();
        if (!value.contains("://")) {
            return KEY.matcher(value).matches() && !value.contains("..") ? value : null;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost();
        boolean ours = bucketHost.equalsIgnoreCase(host)
                || (!properties.privateBucket() && host != null
                && host.equalsIgnoreCase(URI.create(properties.publicBaseUrl()).getHost()));
        if (!ours || uri.getRawPath() == null || uri.getRawPath().length() < 2) {
            return null;
        }
        String key = URLDecoder.decode(uri.getRawPath().substring(1), StandardCharsets.UTF_8);
        return KEY.matcher(key).matches() && !key.contains("..") ? key : null;
    }

    /** True when {@code key} lies in the caller's namespace, e.g. a player's own KYC folder. */
    public static boolean isUnder(String key, String prefix) {
        return key != null && key.startsWith(normalizePrefix(prefix) + "/");
    }

    private String publicUrl(String key) {
        String base = properties.publicBaseUrl();
        return (base.endsWith("/") ? base : base + "/") + key;
    }

    private static String normalizePrefix(String prefix) {
        String p = prefix == null ? "" : prefix.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || !KEY.matcher(p).matches() || p.contains("..")) {
            throw new IllegalArgumentException("invalid object prefix: " + prefix);
        }
        return p;
    }
}
