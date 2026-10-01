package com.bingo789.game.adapter.support;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The digests and ciphers provider protocols use (MD5 / SHA-256 signatures, AES / DES payload encryption). Keys and
 * IVs are the raw bytes of the configured strings unless a protocol says otherwise. Failures to decrypt surface as
 * {@link IllegalArgumentException}: adapters turn them into {@code CallbackException.auth}.
 */
public final class Ciphers {

    /** How the plaintext is padded before a block cipher without its own padding ("NoPadding" modes). */
    public enum Padding {
        /** PKCS#5/#7 by the JCE. */
        PKCS5,
        /** Zero bytes up to the block size; stripped after decryption. */
        ZERO,
        /** Spaces (0x20) up to the block size; stripped after decryption. */
        SPACE
    }

    private Ciphers() {
    }

    public static String md5Hex(String value) {
        return HexFormat.of().formatHex(digest("MD5", value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(digest("SHA-256", value.getBytes(StandardCharsets.UTF_8)));
    }

    /** @param iv null for ECB */
    public static byte[] aesEncrypt(byte[] plain, byte[] key, byte[] iv, Padding padding) {
        return block("AES", Cipher.ENCRYPT_MODE, plain, key, iv, padding);
    }

    /** @param iv null for ECB */
    public static byte[] aesDecrypt(byte[] sealed, byte[] key, byte[] iv, Padding padding) {
        return block("AES", Cipher.DECRYPT_MODE, sealed, key, iv, padding);
    }

    /** DES (8-byte key); CBC when an IV is given, else ECB. */
    public static byte[] desEncrypt(byte[] plain, byte[] key, byte[] iv, Padding padding) {
        return block("DES", Cipher.ENCRYPT_MODE, plain, key, iv, padding);
    }

    public static byte[] desDecrypt(byte[] sealed, byte[] key, byte[] iv, Padding padding) {
        return block("DES", Cipher.DECRYPT_MODE, sealed, key, iv, padding);
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] block(String algorithm, int mode, byte[] input, byte[] key, byte[] iv, Padding padding) {
        String transformation = algorithm + (iv == null ? "/ECB/" : "/CBC/") + (padding == Padding.PKCS5 ? "PKCS5Padding" : "NoPadding");
        try {
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec keySpec = new SecretKeySpec(key, algorithm);
            if (iv == null) {
                cipher.init(mode, keySpec);
            } else {
                cipher.init(mode, keySpec, new IvParameterSpec(iv));
            }
            int blockSize = cipher.getBlockSize();
            if (mode == Cipher.ENCRYPT_MODE) {
                return cipher.doFinal(padding == Padding.PKCS5 ? input : pad(input, blockSize, padding == Padding.ZERO ? 0 : ' '));
            }
            if (padding != Padding.PKCS5 && input.length % blockSize != 0) {
                throw new IllegalArgumentException("ciphertext is not a whole number of blocks");
            }
            byte[] plain = cipher.doFinal(input);
            return padding == Padding.PKCS5 ? plain : unpad(plain, padding == Padding.ZERO ? 0 : ' ');
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(transformation + " failed: " + e.getMessage(), e);
        }
    }

    private static byte[] pad(byte[] input, int blockSize, int fill) {
        int length = (input.length + blockSize - 1) / blockSize * blockSize;
        if (length == 0) {
            length = blockSize;
        }
        byte[] padded = Arrays.copyOf(input, length);
        Arrays.fill(padded, input.length, length, (byte) fill);
        return padded;
    }

    private static byte[] unpad(byte[] plain, int fill) {
        int end = plain.length;
        while (end > 0 && plain[end - 1] == fill) {
            end--;
        }
        return Arrays.copyOf(plain, end);
    }

    private static byte[] digest(String algorithm, byte[] input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
