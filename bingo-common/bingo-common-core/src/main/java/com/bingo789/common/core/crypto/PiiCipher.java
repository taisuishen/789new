package com.bingo789.common.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Field-level encryption of personal data (RA 10173) before it reaches the database: AES-256-GCM with a random
 * 12-byte IV per value, stored as {@code <keyId>:<Base64(iv || ciphertext || tag)>}. The keys are DEW/CSMS secrets
 * (CSMS encrypts them with KMS at rest), resolved once at startup; nothing leaves the pod.
 * <p>
 * Key rotation: add a new key id, make it current (new writes use it), keep the old one for reading. A value without
 * a key id prefix is returned unchanged by {@link #decrypt}, so rows written before encryption stay readable.
 * <p>
 * Encrypted values cannot be searched or made unique, so every such lookup uses a BLIND INDEX:
 * {@link #blindIndex} = HMAC-SHA256 with a separate key over the normalized value, hex. The index key is never
 * rotated (every stored index would change).
 */
public final class PiiCipher {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final Map<String, SecretKeySpec> keys;
    private final String currentKeyId;
    private final byte[] indexKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param keys         key id -> Base64 of 32 random bytes (e.g. {@code openssl rand -base64 32})
     * @param currentKeyId key used for new values
     * @param indexKey     blind-index HMAC key, at least 32 characters
     */
    public PiiCipher(Map<String, String> keys, String currentKeyId, String indexKey) {
        if (keys == null || !keys.containsKey(currentKeyId)) {
            throw new IllegalArgumentException("bingo.pii.keys must contain the current key " + currentKeyId);
        }
        if (indexKey == null || indexKey.length() < 32) {
            throw new IllegalArgumentException("bingo.pii.index-key must be at least 32 characters");
        }
        this.keys = keys.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> aesKey(e.getKey(), e.getValue())));
        this.currentKeyId = currentKeyId;
        this.indexKey = indexKey.getBytes(StandardCharsets.UTF_8);
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), new GCMParameterSpec(TAG_BITS, iv));
            // the key id is authenticated too: a value cannot be moved under another key id
            cipher.updateAAD(currentKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return currentKeyId + ':' + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        int colon = stored.indexOf(':');
        SecretKeySpec key = colon > 0 ? keys.get(stored.substring(0, colon)) : null;
        if (key == null) {
            // written before encryption (or not a ciphertext at all)
            return stored;
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(colon + 1));
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(stored.substring(0, colon).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("PII decryption failed (wrong key or corrupted value)", e);
        }
    }

    /** @param normalized the value exactly as it must match (callers lower-case emails, use E.164 phones, ...) */
    public String blindIndex(String normalized) {
        return normalized == null ? null : HexFormat.of().formatHex(
                Hmacs.hmacSha256(indexKey, normalized.getBytes(StandardCharsets.UTF_8)));
    }

    private static SecretKeySpec aesKey(String id, String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64 == null ? "" : base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bingo.pii.keys." + id + " is not Base64", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException("bingo.pii.keys." + id + " must be 32 bytes (AES-256), got " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }
}
