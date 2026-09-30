package com.bingo789.payment.common;

public final class Texts {

    private Texts() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Keeps free-text values (channel / wallet messages) within their column size. */
    public static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
