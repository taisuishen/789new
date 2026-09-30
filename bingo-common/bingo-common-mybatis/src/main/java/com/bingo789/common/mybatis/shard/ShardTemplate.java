package com.bingo789.common.mybatis.shard;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The only way to select a shard. Every data access of a sharded service runs inside one of these scopes;
 * a transaction must be opened inside the scope (the connection is bound to the shard at transaction start).
 * With sharding disabled all methods simply run the action against the single datasource.
 */
public class ShardTemplate {

    private final ShardRouter router;
    private final boolean enabled;

    public ShardTemplate(ShardRouter router, boolean enabled) {
        this.router = router;
        this.enabled = enabled;
    }

    /** Runs {@code action} on the shard that owns {@code userId}. */
    public <T> T forUser(long userId, Supplier<T> action) {
        return on(router.dataSourceOf(userId), action);
    }

    public void forUser(long userId, Runnable action) {
        forUser(userId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Like {@link #forUser} for writes: refuses with a retryable error while the user's logical shard is being
     * migrated, so no write can land on the old database after its data was copied.
     */
    public <T> T forUserWrite(long userId, Supplier<T> action) {
        if (enabled && router.isMigrating(userId)) {
            throw new ShardMigratingException(router.logicalShard(userId));
        }
        return forUser(userId, action);
    }

    /** Non-sharded tables (checkpoints, configuration) live on the global datasource. */
    public <T> T onGlobal(Supplier<T> action) {
        return on(router.globalDataSource(), action);
    }

    /**
     * Runs {@code action} once per active datasource (one that owns logical shards, or the global one), e.g. for scan
     * and maintenance jobs. The argument is the datasource name.
     */
    public void forEachDataSource(Consumer<String> action) {
        for (String dataSource : router.activeDataSources()) {
            on(dataSource, () -> {
                action.accept(dataSource);
                return null;
            });
        }
    }

    /**
     * Runs {@code action} on a datasource by name, typically after grouping a batch of users with
     * {@code router().dataSourceOf(userId)} so one write per shard replaces one write per user.
     */
    public <T> T onDataSource(String dataSource, Supplier<T> action) {
        if (enabled && !router.dataSources().contains(dataSource)) {
            throw new IllegalArgumentException("unknown datasource " + dataSource);
        }
        return on(dataSource, action);
    }

    public ShardRouter router() {
        return router;
    }

    private <T> T on(String dataSource, Supplier<T> action) {
        if (!enabled) {
            return action.get();
        }
        String current = ShardContext.current();
        if (current != null && !current.equals(dataSource) && TransactionSynchronizationManager.isActualTransactionActive()) {
            // a transaction is bound to one connection: switching shards inside it would silently hit the wrong database
            throw new IllegalStateException("cannot switch from shard " + current + " to " + dataSource + " inside a transaction");
        }
        String previous = ShardContext.swap(dataSource);
        try {
            return action.get();
        } finally {
            ShardContext.swap(previous);
        }
    }
}
