package com.bingo789.common.core.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * The platform runs on UTC+8 everywhere: JVM default time zone, database server and sessions
 * ({@code default-time-zone=+08:00}, JDBC {@code connectionTimeZone=+08:00}), middleware containers
 * ({@code TZ=Asia/Manila}, UTC+8 without DST), XXL-Job cron schedules and business days.
 * <p>
 * Every {@code LocalDateTime} / DATETIME value in the system is UTC+8 wall-clock time. {@link Instant} values are
 * absolute and convert through {@link #ZONE}. Never use {@code ZoneOffset.UTC} or {@code ZoneId.systemDefault()}.
 */
public final class BingoTime {

    public static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    private BingoTime() {
    }

    /** Call first thing in every {@code main}, so logs, schedulers and libraries agree with the database. */
    public static void applyJvmDefault() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZONE);
    }

    public static Instant toInstant(LocalDateTime local) {
        return local == null ? null : local.toInstant(ZONE);
    }
}
