package com.bingo789.common.obs;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.time.BingoTime;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

/** Key rules and image checks shared by the {@link ObsStorage} implementations. */
final class ObjectKeys {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    /** Keys we generate: path segments of letters, digits, '-', '_', '.'. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,1000}");

    private ObjectKeys() {
    }

    /** @return the image's real format; throws BizException (400) for empty, too large or non-image data */
    static ImageFormat checkImage(byte[] data, ObsProperties properties) {
        BizException.check(data != null && data.length > 0, CommonErrorCode.BAD_REQUEST, "empty file");
        BizException.check(data.length <= properties.maxImageSize().toBytes(), CommonErrorCode.BAD_REQUEST,
                "image larger than " + properties.maxImageSize().toMegabytes() + "MB");
        ImageFormat format = ImageFormat.detect(data);
        BizException.check(format != null, CommonErrorCode.BAD_REQUEST, "only JPEG, PNG or WebP images are accepted");
        return format;
    }

    static String newKey(String prefix, ImageFormat format) {
        return normalizePrefix(prefix) + "/" + LocalDate.now(BingoTime.ZONE).format(DAY) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + format.extension();
    }

    /** @return the key when it has our shape (no "..", no leading '/'), else null */
    static String validKey(String key) {
        return key != null && KEY.matcher(key).matches() && !key.contains("..") ? key : null;
    }

    static String normalizePrefix(String prefix) {
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
