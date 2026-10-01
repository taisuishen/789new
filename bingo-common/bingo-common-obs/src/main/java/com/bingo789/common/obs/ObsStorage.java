package com.bingo789.common.obs;

import java.time.Duration;

/**
 * Object storage for uploaded images. Objects are addressed by key ({@code <prefix>/<yyyyMMdd>/<uuid>.<ext>}); the key
 * is what callers persist, URLs are derived on demand. Huawei OBS in every deployed environment
 * ({@link HuaweiObsStorage}); {@link LocalDiskStorage} for local development only ({@code bingo.obs.local-dir}).
 */
public interface ObsStorage {

    /**
     * Stores an image under {@code prefix} after checking its real format (first bytes) and size.
     *
     * @param prefix e.g. "kyc/123456": the caller's namespace, later checked with {@link #isUnder}
     * @throws com.bingo789.common.core.BizException not an accepted image, or too large (400)
     * @throws com.bingo789.common.core.RetryableException storage unavailable (503)
     */
    StoredObject uploadImage(String prefix, byte[] data);

    /** Address a client (or a worker such as RunPod) can fetch now. */
    String url(String key);

    /** Expiring GET address of the object. */
    String signedUrl(String key, Duration ttl);

    boolean exists(String key);

    /**
     * Maps what a client sent back (a URL this storage handed out, signed or public, or a bare key) to the object key.
     *
     * @return the key, or null when the value does not point into this storage
     */
    String keyOf(String urlOrKey);

    /** True when {@code key} lies in the caller's namespace, e.g. a player's own KYC folder. */
    static boolean isUnder(String key, String prefix) {
        return key != null && key.startsWith(ObjectKeys.normalizePrefix(prefix) + "/");
    }
}
