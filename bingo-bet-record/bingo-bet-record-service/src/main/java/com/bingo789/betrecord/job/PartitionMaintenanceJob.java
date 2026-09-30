package com.bingo789.betrecord.job;

import com.bingo789.betrecord.config.BetRecordProperties;
import com.bingo789.common.mybatis.MonthlyPartitions;
import com.bingo789.common.mybatis.MonthlyPartitions.Bound;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Monthly partitions of every shard database: keeps the next two months ready (p_future stays empty) and drops months
 * older than bingo.bet-record.partition-keep-months with DROP PARTITION. Schedule daily (e.g. 04:00 UTC+8); running it
 * more often is harmless. Report history lives in StarRocks (loaded from Kafka), so dropping here loses nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionMaintenanceJob {

    private static final int MONTHS_AHEAD = 2;

    private final JdbcTemplate jdbc;
    private final ShardTemplate shards;
    private final BetRecordProperties properties;

    @XxlJob("betRecordPartitionJob")
    public void betRecordPartitionJob() {
        int keep = properties.partitionKeepMonths();
        shards.forEachDataSource(dataSource -> {
            MonthlyPartitions.maintain(jdbc, "game_round", Bound.DATE, MONTHS_AHEAD, keep);
            MonthlyPartitions.maintain(jdbc, "provider_bet_record", Bound.DATETIME, MONTHS_AHEAD, keep);
            MonthlyPartitions.maintain(jdbc, "round_txn", Bound.SNOWFLAKE_ID, MONTHS_AHEAD, keep);
            XxlJobHelper.log("partitions maintained on {}", dataSource);
        });
    }
}
