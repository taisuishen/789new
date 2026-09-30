package com.bingo789.user.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Client attributes recorded at login, normalized to fit user_login_log. */
public record ClientInfo(String ip, String deviceId, String userAgent, String countryCode) {

    private static final Pattern COUNTRY = Pattern.compile("[A-Za-z]{2}");

    public ClientInfo {
        ip = ip == null || ip.isBlank() ? "unknown" : truncate(ip, 45);
        deviceId = truncate(deviceId, 128);
        userAgent = truncate(userAgent, 512);
        countryCode = countryCode != null && COUNTRY.matcher(countryCode).matches() ? countryCode.toUpperCase(Locale.ROOT) : null;
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
