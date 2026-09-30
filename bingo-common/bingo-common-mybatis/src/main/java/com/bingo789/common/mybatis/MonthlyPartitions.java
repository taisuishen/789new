package com.bingo789.common.mybatis;

import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.time.BingoTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maintenance of monthly RANGE partitions named {@code pYYYYMM} (upper bound = first day of the next month, UTC+8)
 * followed by {@code p_future}: adds the coming months by splitting {@code p_future} (instant while it is empty) and
 * drops months older than the retention with {@code DROP PARTITION} (a DDL: no row events, no row-by-row delete).
 * Runs against the datasource of the current shard scope (call it once per shard database).
 */
@Slf4j
public final class MonthlyPartitions {

    /** Type of the partition bound. */
    public enum Bound {
        /** RANGE COLUMNS (DATE column): '2026-11-01' */
        DATE,
        /** RANGE COLUMNS (DATETIME column): '2026-11-01 00:00:00' */
        DATETIME,
        /** RANGE (snowflake id): the first id of the month */
        SNOWFLAKE_ID
    }

    private static final Pattern MONTHLY = Pattern.compile("p(\\d{6})");
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Pattern SAFE_TABLE = Pattern.compile("[a-z_][a-z0-9_]{0,63}");

    private MonthlyPartitions() {
    }

    /**
     * @param monthsAhead months after the current one that must already have a partition
     * @param keepMonths  full months kept before the current one; older partitions are dropped
     */
    public static void maintain(JdbcTemplate jdbc, String table, Bound bound, int monthsAhead, int keepMonths) {
        if (!SAFE_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("invalid table name " + table);
        }
        YearMonth current = YearMonth.from(LocalDate.now(BingoTime.ZONE));
        List<String> existing = jdbc.queryForList("""
                SELECT PARTITION_NAME FROM information_schema.PARTITIONS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME IS NOT NULL
                 ORDER BY PARTITION_ORDINAL_POSITION
                """, String.class, table);
        if (!existing.contains("p_future")) {
            throw new IllegalStateException(table + " has no p_future partition");
        }
        YearMonth last = existing.stream().map(MONTHLY::matcher).filter(Matcher::matches)
                .map(m -> YearMonth.parse(m.group(1), YYYYMM)).max(YearMonth::compareTo).orElse(current.minusMonths(1));

        for (YearMonth month = last.plusMonths(1); !month.isAfter(current.plusMonths(monthsAhead)); month = month.plusMonths(1)) {
            Long rows = jdbc.queryForObject("SELECT TABLE_ROWS FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = DATABASE()"
                    + " AND TABLE_NAME = ? AND PARTITION_NAME = 'p_future'", Long.class, table);
            if (rows != null && rows > 0) {
                log.warn("ALERT {}.p_future holds ~{} rows: splitting it copies them (partitions were not added in time)",
                        table, rows);
            }
            String max = bound == Bound.SNOWFLAKE_ID ? "MAXVALUE" : "(MAXVALUE)";
            jdbc.execute("ALTER TABLE " + table + " REORGANIZE PARTITION p_future INTO (PARTITION p" + month.format(YYYYMM)
                    + " VALUES LESS THAN (" + literal(bound, month.plusMonths(1)) + "), PARTITION p_future VALUES LESS THAN "
                    + max + ")");
            log.info("{}: partition p{} added", table, month.format(YYYYMM));
        }

        YearMonth oldestKept = current.minusMonths(keepMonths);
        for (String name : existing) {
            Matcher m = MONTHLY.matcher(name);
            if (m.matches() && YearMonth.parse(m.group(1), YYYYMM).isBefore(oldestKept)) {
                jdbc.execute("ALTER TABLE " + table + " DROP PARTITION " + name);
                log.info("{}: partition {} dropped (retention {} months)", table, name, keepMonths);
            }
        }
    }

    /** Upper bound of the partition ending at the start of {@code nextMonth} (UTC+8). */
    static String literal(Bound bound, YearMonth nextMonth) {
        LocalDate start = nextMonth.atDay(1);
        return switch (bound) {
            case DATE -> "'" + start + "'";
            case DATETIME -> "'" + start + " 00:00:00'";
            case SNOWFLAKE_ID -> Long.toString((start.atStartOfDay(BingoTime.ZONE).toInstant().toEpochMilli()
                    - SnowflakeIdGenerator.EPOCH) << 22);
        };
    }
}
