package com.bingo789.common.obs;

import com.bingo789.common.core.RetryableException;
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

/**
 * {@link ObsStorage} on Huawei OBS. URLs are derived on demand: the public URL for a public bucket behind a custom
 * domain, a signed expiring URL for a private bucket (the default for identity documents).
 */
@Slf4j
public class HuaweiObsStorage implements ObsStorage {

    private final ObsClient client;
    private final ObsProperties properties;
    private final String bucketHost;

    public HuaweiObsStorage(ObsClient client, ObsProperties properties) {
        this.client = client;
        this.properties = properties;
        String endpointHost = URI.create(properties.endpoint().contains("://") ? properties.endpoint()
                : "https://" + properties.endpoint()).getHost();
        this.bucketHost = properties.bucket() + "." + endpointHost;
    }

    @Override
    public StoredObject uploadImage(String prefix, byte[] data) {
        ImageFormat format = ObjectKeys.checkImage(data, properties);
        String key = ObjectKeys.newKey(prefix, format);
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

    @Override
    public String url(String key) {
        return properties.privateBucket() ? signedUrl(key, properties.signedUrlTtl()) : publicUrl(key);
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        TemporarySignatureRequest request = new TemporarySignatureRequest(HttpMethodEnum.GET, ttl.toSeconds());
        request.setBucketName(properties.bucket());
        request.setObjectKey(key);
        TemporarySignatureResponse response = client.createTemporarySignature(request);
        return response.getSignedUrl();
    }

    @Override
    public boolean exists(String key) {
        try {
            return client.doesObjectExist(properties.bucket(), key);
        } catch (ObsException e) {
            throw new RetryableException("image storage temporarily unavailable");
        }
    }

    @Override
    public String keyOf(String urlOrKey) {
        if (urlOrKey == null || urlOrKey.isBlank()) {
            return null;
        }
        String value = urlOrKey.trim();
        if (!value.contains("://")) {
            return ObjectKeys.validKey(value);
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
        return ObjectKeys.validKey(URLDecoder.decode(uri.getRawPath().substring(1), StandardCharsets.UTF_8));
    }

    private String publicUrl(String key) {
        String base = properties.publicBaseUrl();
        return (base.endsWith("/") ? base : base + "/") + key;
    }
}
