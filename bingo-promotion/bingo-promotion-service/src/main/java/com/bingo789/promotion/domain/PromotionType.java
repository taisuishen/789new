package com.bingo789.promotion.domain;

import java.util.Locale;

/**
 * Decides the schema of promotion.config_json. The column is a plain string: a pod that does not know a newer type
 * still lists and displays such promotions, it just never applies their terms.
 */
public enum PromotionType {
    FIRST_DEPOSIT,
    REBATE;

    /** @return the type, or null when {@code value} is not a type this version knows */
    public static PromotionType parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
