package com.bingo789.common.obs;

import com.bingo789.common.core.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskStorageTest {

    private static final String SIGNING_KEY = "local-signing-key-of-at-least-32-chars";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @TempDir
    Path dir;

    private LocalDiskStorage storage(String baseUrl) {
        return new LocalDiskStorage(new ObsProperties(true, null, null, null, null, null, Duration.ofMinutes(15),
                DataSize.ofMegabytes(10), "/mnt/csms", dir.toString(), baseUrl, SIGNING_KEY));
    }

    @Test
    void storesTheFileAndHandsOutASignedExpiringUrl() throws Exception {
        LocalDiskStorage storage = storage("https://abc.trycloudflare.com/files/");

        StoredObject stored = storage.uploadImage("kyc/42", PNG);

        assertThat(stored.key()).startsWith("kyc/42/").endsWith(".png");
        assertThat(Files.readAllBytes(dir.resolve(stored.key()))).isEqualTo(PNG);
        assertThat(storage.exists(stored.key())).isTrue();
        URI url = URI.create(stored.url());
        assertThat(url.getHost()).isEqualTo("abc.trycloudflare.com");
        assertThat(url.getPath()).isEqualTo("/files/" + stored.key());
        long expires = Long.parseLong(url.getQuery().replaceAll(".*expires=(\\d+).*", "$1"));
        assertThat(expires).isBetween(Instant.now().getEpochSecond() + 800, Instant.now().getEpochSecond() + 901);
        assertThat(url.getQuery()).endsWith("sig=" + storage.sign(stored.key(), expires));
    }

    @Test
    void keysComeBackFromUrlsEvenAfterTheTunnelHostChanged() {
        LocalDiskStorage storage = storage("https://abc.trycloudflare.com/files");
        String key = "kyc/42/20261001/a.png";

        assertThat(storage.keyOf("https://other.trycloudflare.com/files/" + key + "?expires=1&sig=x")).isEqualTo(key);
        assertThat(storage.keyOf(key)).isEqualTo(key);
        assertThat(storage.keyOf("https://abc.trycloudflare.com/elsewhere/" + key)).isNull();
        assertThat(storage.keyOf("kyc/42/../43/a.png")).isNull();
        assertThat(storage.exists("kyc/42/../../etc/passwd")).isFalse();
    }

    @Test
    void theBaseUrlIsReadFromTheTunnelFileWhenNotConfigured() throws Exception {
        LocalDiskStorage storage = storage("");
        Files.writeString(dir.resolve(LocalDiskStorage.BASE_URL_FILE), "https://first.trycloudflare.com/files\n");
        assertThat(storage.url("kyc/1/20261001/a.png")).startsWith("https://first.trycloudflare.com/files/kyc/1/");

        Files.writeString(dir.resolve(LocalDiskStorage.BASE_URL_FILE), "https://second.trycloudflare.com/files");
        assertThat(storage.url("kyc/1/20261001/a.png")).startsWith("https://second.trycloudflare.com/files/kyc/1/");
    }

    @Test
    void rejectsNonImagesAndAShortSigningKey() {
        assertThatThrownBy(() -> storage("https://x/files").uploadImage("kyc/42", "<svg/>".getBytes()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new LocalDiskStorage(new ObsProperties(true, null, null, null, null, null,
                Duration.ofMinutes(15), DataSize.ofMegabytes(10), "/mnt/csms", dir.toString(), "", "short")))
                .isInstanceOf(IllegalStateException.class);
    }
}
