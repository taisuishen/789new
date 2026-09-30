package com.bingo789.common.mybatis;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyPartitionsTest {

    @Test
    void boundsMatchTheShippedDdl() {
        YearMonth october = YearMonth.of(2026, 10);
        assertThat(MonthlyPartitions.literal(MonthlyPartitions.Bound.DATE, october)).isEqualTo("'2026-10-01'");
        assertThat(MonthlyPartitions.literal(MonthlyPartitions.Bound.DATETIME, october)).isEqualTo("'2026-10-01 00:00:00'");
        // 05_bet_record.sql: round_txn p202609 VALUES LESS THAN (231082662297600000) -- 2026-10-01T00:00+08:00
        assertThat(MonthlyPartitions.literal(MonthlyPartitions.Bound.SNOWFLAKE_ID, october)).isEqualTo("231082662297600000");
    }
}
