package com.bingo789.turnover.job;

import com.bingo789.common.mybatis.MonthlyPartitions;
import com.bingo789.common.mybatis.MonthlyPartitions.Bound;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.turnover.config.TurnoverProperties;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Monthly partitions of turnover_record on every shard database (next two months ready, months older than
 * bingo.turnover.record-keep-months dropped). Schedule daily. Keep enough months for the longest-lived bucket's
 * records to stay visible to customer service.
 */
@Component
@RequiredArgsConstructor
public class PartitionMaintenanceJob {

    private static final int MONTHS_AHEAD = 2;

    private final JdbcTemplate jdbc;
    private final ShardTemplate shards;
    private final TurnoverProperties properties;

    @XxlJob("turnoverPartitionJob")
    public void turnoverPartitionJob() {
        shards.forEachDataSource(dataSource -> {
            MonthlyPartitions.maintain(jdbc, "turnover_record", Bound.DATE, MONTHS_AHEAD, properties.recordKeepMonths());
            XxlJobHelper.log("partitions maintained on {}", dataSource);
        });
    }
}
