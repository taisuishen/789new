package com.bingo789.reconcile.model;

import java.time.LocalDateTime;
import java.util.Comparator;

/**
 * Aggregate row key (the primary key order of the hourly tables). Batches upsert their rows sorted by this key, so
 * concurrent consumers lock shared rows in the same order and cannot deadlock each other.
 *
 * @param userLine the player's line carried by the event (snapshot at event time)
 */
public record HourKey(LocalDateTime statHour, int userLine, String providerCode, String gameCode, String currency)
        implements Comparable<HourKey> {

    private static final Comparator<HourKey> ORDER = Comparator.comparing(HourKey::statHour)
            .thenComparingInt(HourKey::userLine)
            .thenComparing(HourKey::providerCode)
            .thenComparing(HourKey::gameCode)
            .thenComparing(HourKey::currency);

    @Override
    public int compareTo(HourKey other) {
        return ORDER.compare(this, other);
    }
}
