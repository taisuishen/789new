package com.bingo789.promotion.domain;

import java.util.Locale;

public enum PromotionStatus {
    /** Being prepared; never visible, never applied. */
    DRAFT,
    /** Live inside [start_time, end_time). */
    ONLINE,
    /** Taken down; can be put ONLINE again. */
    OFFLINE;

    /** A published promotion never returns to DRAFT. */
    public boolean canMoveTo(PromotionStatus target) {
        return switch (this) {
            case DRAFT -> target == ONLINE || target == OFFLINE;
            case ONLINE -> target == OFFLINE;
            case OFFLINE -> target == ONLINE;
        };
    }

    /** @return the status, or null when {@code value} is not a status name */
    public static PromotionStatus parse(String value) {
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
