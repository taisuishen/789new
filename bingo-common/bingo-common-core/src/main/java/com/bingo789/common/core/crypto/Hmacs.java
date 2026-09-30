package com.bingo789.common.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

public final class Hmacs {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private Hmacs() {
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String hmacSha256Hex(String key, String data) {
        return HexFormat.of().formatHex(hmacSha256(key.getBytes(StandardCharsets.UTF_8), data.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hmacSha256Base64(String key, String data) {
        return Base64.getEncoder().encodeToString(hmacSha256(key.getBytes(StandardCharsets.UTF_8), data.getBytes(StandardCharsets.UTF_8)));
    }

    /** Constant-time comparison; always use this when checking signatures. */
    public static boolean safeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
