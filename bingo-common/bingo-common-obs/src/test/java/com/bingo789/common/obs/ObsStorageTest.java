package com.bingo789.common.obs;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ObsStorageTest {

    private static final ObsProperties PRIVATE = new ObsProperties(true, "https://obs.ap-southeast-3.myhuaweicloud.com",
            "bingo-kyc", null, null, null, Duration.ofMinutes(15), DataSize.ofMegabytes(10), "/mnt/csms");
    private static final ObsProperties PUBLIC = new ObsProperties(true, "obs.ap-southeast-3.myhuaweicloud.com",
            "bingo-img", null, null, "https://img.789bingo.com", Duration.ofMinutes(15), DataSize.ofMegabytes(10), "/mnt/csms");

    @Test
    void detectsFormatsFromMagicBytesOnly() {
        assertThat(ImageFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo(ImageFormat.JPEG);
        assertThat(ImageFormat.detect(bytes(0x89, 'P', 'N', 'G'))).isEqualTo(ImageFormat.PNG);
        assertThat(ImageFormat.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes())).isEqualTo(ImageFormat.WEBP);
        assertThat(ImageFormat.detect("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes())).isNull();
    }

    @Test
    void mapsOurUrlsBackToKeysAndRejectsForeignOnes() {
        ObsStorage storage = new ObsStorage(null, PRIVATE);
        String key = "kyc/42/20261001/abc.jpg";
        assertThat(storage.keyOf("https://bingo-kyc.obs.ap-southeast-3.myhuaweicloud.com/" + key
                + "?AccessKeyId=x&Expires=1&Signature=y")).isEqualTo(key);
        assertThat(storage.keyOf(key)).isEqualTo(key);
        assertThat(storage.keyOf("https://evil.example.com/" + key)).isNull();
        assertThat(storage.keyOf("kyc/42/../43/x.jpg")).isNull();

        ObsStorage publicStorage = new ObsStorage(null, PUBLIC);
        assertThat(publicStorage.keyOf("https://img.789bingo.com/" + key)).isEqualTo(key);
    }

    @Test
    void namespaceCheck() {
        assertThat(ObsStorage.isUnder("kyc/42/20261001/a.jpg", "kyc/42")).isTrue();
        assertThat(ObsStorage.isUnder("kyc/420/20261001/a.jpg", "kyc/42")).isFalse();
    }

    private static byte[] bytes(int... head) {
        byte[] data = new byte[16];
        for (int i = 0; i < head.length; i++) {
            data[i] = (byte) head[i];
        }
        return data;
    }
}
