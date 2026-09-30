package com.bingo789.risk.common;

public final class Texts {

    private Texts() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
