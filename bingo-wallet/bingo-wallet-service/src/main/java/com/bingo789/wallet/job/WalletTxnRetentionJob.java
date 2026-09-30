package com.bingo789.wallet.job;

import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.wallet.config.WalletProperties;
import com.bingo789.wallet.mapper.WalletTxnMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Keeps wallet_txn at the idempotency window (bingo.wallet.retention.keep, default 7 days): history is in StarRocks,
 * loaded from bingo.wallet.txn, and the CDC job ignores deletes. Schedule it off-peak (e.g. daily 05:00-11:00 UTC+8,
 * every 10 minutes) and only once the StarRocks load of bingo.wallet.txn is running.
 * <p>
 * All shard databases in parallel; each deletes the oldest rows in small batches and pauses at least as long as the
 * last batch took, so a busy database automatically gets less delete load.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletTxnRetentionJob {

    private final WalletTxnMapper txnMapper;
    private final ShardTemplate shards;
    private final WalletProperties properties;

    @XxlJob("walletTxnRetentionJob")
    public void walletTxnRetentionJob() throws Exception {
        WalletProperties.Retention retention = properties.retention();
        long beforeId = (Instant.now().minus(retention.keep()).toEpochMilli() - SnowflakeIdGenerator.EPOCH) << 22;
        Instant deadline = Instant.now().plus(retention.maxRunTime());
        long total = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> results = new ArrayList<>();
            for (String dataSource : shards.router().activeDataSources()) {
                results.add(pool.submit(() -> shards.onDataSource(dataSource, () -> purge(dataSource, beforeId, retention, deadline))));
            }
            for (Future<Long> result : results) {
                total += result.get();
            }
        }
        log.info("wallet_txn retention: {} rows older than {} deleted", total, retention.keep());
        XxlJobHelper.log("deleted={}", total);
    }

    private long purge(String dataSource, long beforeId, WalletProperties.Retention retention, Instant deadline) {
        long deleted = 0;
        while (Instant.now().isBefore(deadline)) {
            long started = System.nanoTime();
            int rows = txnMapper.deleteOlderThan(beforeId, retention.batchSize());
            deleted += rows;
            if (rows < retention.batchSize()) {
                break;
            }
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            sleep(took.compareTo(retention.pauseBetweenBatches()) > 0 ? took : retention.pauseBetweenBatches());
        }
        if (deleted > 0) {
            log.info("wallet_txn retention on {}: {} rows deleted", dataSource, deleted);
        }
        return deleted;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
