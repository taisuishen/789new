package com.bingo789.promotion.common;

public final class Texts {

    private Texts() {
    }

    public static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
