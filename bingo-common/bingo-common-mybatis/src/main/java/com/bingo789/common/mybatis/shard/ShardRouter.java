package com.bingo789.common.mybatis.shard;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Routing table: logical shard -> datasource name, plus the set of shards currently being migrated.
 * Immutable snapshots swapped atomically, so a reload never exposes a half-built table.
 */
@Slf4j
public class ShardRouter {

    /** Datasource name used when sharding is disabled (the application's single DataSource). */
    public static final String SINGLE = "default";

    private final int logicalShards;
    private final List<String> dataSources;
    private final String globalDataSource;
    private volatile Table table;

    private ShardRouter(int logicalShards, List<String> dataSources, String globalDataSource, Table table) {
        this.logicalShards = logicalShards;
        this.dataSources = List.copyOf(dataSources);
        this.globalDataSource = globalDataSource;
        this.table = table;
    }

    /** Sharding disabled: everything lives in the single datasource. */
    public static ShardRouter single(int logicalShards) {
        String[] targets = new String[logicalShards];
        Arrays.fill(targets, SINGLE);
        return new ShardRouter(logicalShards, List.of(SINGLE), SINGLE, new Table(targets, new BitSet(), List.of(SINGLE)));
    }

    public static ShardRouter of(ShardProperties properties) {
        List<String> names = new ArrayList<>(properties.datasources().keySet());
        if (names.isEmpty()) {
            throw new IllegalStateException("bingo.shard.enabled=true but no bingo.shard.datasources are configured");
        }
        names.sort(null);
        String global = properties.globalDatasource() == null || properties.globalDatasource().isBlank()
                ? names.getFirst() : properties.globalDatasource();
        if (!names.contains(global)) {
            throw new IllegalStateException("bingo.shard.global-datasource " + global + " is not a configured datasource");
        }
        return new ShardRouter(properties.logicalShards(), names, global, buildTable(properties, Set.copyOf(names), global));
    }

    /** Applies new routes / migrating shards (from a Nacos refresh). Invalid input is rejected, the old table stays. */
    public void reload(ShardProperties properties) {
        if (properties.logicalShards() != logicalShards) {
            throw new IllegalStateException("the logical shard count cannot change at runtime (" + logicalShards
                    + " -> " + properties.logicalShards() + ")");
        }
        if (!Set.copyOf(dataSources).equals(properties.datasources().keySet())) {
            throw new IllegalStateException("datasources cannot change at runtime; roll out a restart with the new datasource first");
        }
        Table next = buildTable(properties, Set.copyOf(dataSources), globalDataSource);
        Table previous = table;
        table = next;
        log.warn("shard routes reloaded: {} logical shards moved, {} shards migrating",
                previous.movedTo(next), next.migrating().cardinality());
    }

    public int logicalShard(long userId) {
        return LogicalShards.of(userId, logicalShards);
    }

    public String dataSourceOf(long userId) {
        return table.targets()[logicalShard(userId)];
    }

    public boolean isMigrating(long userId) {
        return table.migrating().get(logicalShard(userId));
    }

    /** Every configured datasource, including one that currently owns no logical shard. */
    public List<String> dataSources() {
        return dataSources;
    }

    /**
     * Datasources that currently own at least one logical shard, plus the global datasource. Scan and maintenance
     * jobs run on these only, so a database being filled by DRS for a move, or left behind after one, is never
     * touched by them.
     */
    public List<String> activeDataSources() {
        return table.active();
    }

    public String globalDataSource() {
        return globalDataSource;
    }

    public int logicalShards() {
        return logicalShards;
    }

    private static Table buildTable(ShardProperties properties, Set<String> knownDataSources, String globalDataSource) {
        int n = properties.logicalShards();
        String[] targets = new String[n];
        for (ShardProperties.Route route : properties.routes()) {
            if (!knownDataSources.contains(route.datasource())) {
                throw new IllegalStateException("route " + route.shards() + " points to unknown datasource " + route.datasource());
            }
            for (int shard : parseRange(route.shards(), n)) {
                if (targets[shard] != null) {
                    throw new IllegalStateException("logical shard " + shard + " is routed twice");
                }
                targets[shard] = route.datasource();
            }
        }
        for (int i = 0; i < n; i++) {
            if (targets[i] == null) {
                throw new IllegalStateException("logical shard " + i + " has no route");
            }
        }
        BitSet migrating = new BitSet(n);
        for (String range : properties.migratingShards()) {
            for (int shard : parseRange(range, n)) {
                migrating.set(shard);
            }
        }
        Set<String> active = new TreeSet<>(Arrays.asList(targets));
        active.add(globalDataSource);
        return new Table(targets, migrating, List.copyOf(active));
    }

    /** "5" or "0-63", both ends inclusive. */
    static int[] parseRange(String range, int logicalShards) {
        String value = range.strip();
        int dash = value.indexOf('-');
        int from = Integer.parseInt(dash < 0 ? value : value.substring(0, dash).strip());
        int to = dash < 0 ? from : Integer.parseInt(value.substring(dash + 1).strip());
        if (from < 0 || to >= logicalShards || from > to) {
            throw new IllegalStateException("invalid logical shard range " + range + " (0.." + (logicalShards - 1) + ")");
        }
        int[] shards = new int[to - from + 1];
        for (int i = 0; i < shards.length; i++) {
            shards[i] = from + i;
        }
        return shards;
    }

    private record Table(String[] targets, BitSet migrating, List<String> active) {

        int movedTo(Table next) {
            int moved = 0;
            for (int i = 0; i < targets.length; i++) {
                if (!targets[i].equals(next.targets[i])) {
                    moved++;
                }
            }
            return moved;
        }
    }
}
