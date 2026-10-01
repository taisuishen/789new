package com.bingo789.common.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiCipherTest {

    /** Base64 of "0123456789abcdef0123456789abcdef" / "fedcba9876543210fedcba9876543210". */
    private static final String K1 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String K2 = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final String INDEX_KEY = "unit-test-index-key-0123456789abcdef";

    @Test
    void roundTripsWithAFreshIvEveryTime() {
        PiiCipher cipher = new PiiCipher(Map.of("k1", K1), "k1", INDEX_KEY);
        String a = cipher.encrypt("+639171234567");
        String b = cipher.encrypt("+639171234567");

        assertThat(a).startsWith("k1:").isNotEqualTo(b).doesNotContain("639171234567");
        assertThat(cipher.decrypt(a)).isEqualTo("+639171234567");
        assertThat(cipher.decrypt(b)).isEqualTo("+639171234567");
        assertThat(cipher.encrypt(null)).isNull();
    }

    @Test
    void rotatedKeysKeepOldValuesReadable() {
        String old = new PiiCipher(Map.of("k1", K1), "k1", INDEX_KEY).encrypt("a@b.ph");
        PiiCipher rotated = new PiiCipher(Map.of("k1", K1, "k2", K2), "k2", INDEX_KEY);

        assertThat(rotated.decrypt(old)).isEqualTo("a@b.ph");
        assertThat(rotated.encrypt("a@b.ph")).startsWith("k2:");
    }

    @Test
    void valuesWrittenBeforeEncryptionAreReturnedAsTheyAre() {
        PiiCipher cipher = new PiiCipher(Map.of("k1", K1), "k1", INDEX_KEY);
        assertThat(cipher.decrypt("1990-05-01")).isEqualTo("1990-05-01");
    }

    @Test
    void aTamperedValueIsRejected() {
        PiiCipher cipher = new PiiCipher(Map.of("k1", K1), "k1", INDEX_KEY);
        String sealed = cipher.encrypt("1990-05-01");
        String tampered = sealed.substring(0, sealed.length() - 4) + (sealed.endsWith("AAAA") ? "BBBB" : "AAAA");

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blindIndexIsDeterministicAndKeyed() {
        PiiCipher cipher = new PiiCipher(Map.of("k1", K1), "k1", INDEX_KEY);
        PiiCipher other = new PiiCipher(Map.of("k1", K1), "k1", "another-index-key-0123456789abcdefgh");

        assertThat(cipher.blindIndex("a@b.ph")).hasSize(64).isEqualTo(cipher.blindIndex("a@b.ph"));
        assertThat(cipher.blindIndex("a@b.ph")).isNotEqualTo(other.blindIndex("a@b.ph"));
    }

    @Test
    void rejectsWeakConfiguration() {
        assertThatThrownBy(() -> new PiiCipher(Map.of("k1", "c2hvcnQ="), "k1", INDEX_KEY))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new PiiCipher(Map.of("k1", K1), "k2", INDEX_KEY))
                .hasMessageContaining("current key");
        assertThatThrownBy(() -> new PiiCipher(Map.of("k1", K1), "k1", "short"))
                .hasMessageContaining("index-key");
    }
}
