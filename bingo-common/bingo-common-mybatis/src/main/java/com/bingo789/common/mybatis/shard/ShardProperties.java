package com.bingo789.common.mybatis.shard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * User-based database sharding ({@code bingo.shard.*}).
 * <p>
 * Users map to {@link #logicalShards} logical shards through a fixed hash ({@link LogicalShards}); {@link #routes}
 * map logical shards onto physical {@link #datasources}. Growing capacity = adding a datasource and moving logical
 * shards to it (routes change, the hash never does). {@code routes} and {@code migratingShards} are reloaded from
 * Nacos at runtime; datasources and the logical shard count are fixed for the life of the process.
 *
 * @param datasources      physical databases by name, e.g. ds00..ds15
 * @param routes           e.g. {shards: "0-63", datasource: ds00}; together they must cover every logical shard once
 * @param globalDatasource holds non-sharded tables (checkpoints, config); defaults to the first datasource
 * @param migratingShards  logical shards whose writes are refused (503, retryable) while being moved, e.g. ["64-127"]
 */
@ConfigurationProperties("bingo.shard")
public record ShardProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1024") int logicalShards,
        @DefaultValue Map<String, DataSourceSpec> datasources,
        @DefaultValue List<Route> routes,
        String globalDatasource,
        @DefaultValue List<String> migratingShards,
        @DefaultValue Pool pool) {

    /** @param maxPoolSize overrides {@link Pool#maxPoolSize()} for this datasource */
    public record DataSourceSpec(String url, String username, String password, Integer maxPoolSize) {
    }

    public record Route(String shards, String datasource) {
    }

    /**
     * Every pod holds one pool per datasource, so connections per primary = pods x maxPoolSize. Wallet transactions
     * last ~1-2 ms, so 8 connections serve several thousand transactions per second per pod and shard.
     */
    public record Pool(
            @DefaultValue("8") int maxPoolSize,
            @DefaultValue("2") int minIdle,
            @DefaultValue("1s") Duration connectionTimeout,
            @DefaultValue("30s") Duration keepaliveTime,
            @DefaultValue("10m") Duration maxLifetime,
            @DefaultValue("TRANSACTION_READ_COMMITTED") String transactionIsolation) {
    }
}
