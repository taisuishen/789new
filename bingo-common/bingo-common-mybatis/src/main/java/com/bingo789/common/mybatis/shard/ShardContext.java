package com.bingo789.common.mybatis.shard;

/**
 * Datasource selected for the current thread. Set only through {@link ShardTemplate}; read by
 * {@link ShardRoutingDataSource} when a connection is obtained (for a transaction: once, at its start).
 */
public final class ShardContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ShardContext() {
    }

    public static String current() {
        return CURRENT.get();
    }

    static String swap(String dataSource) {
        String previous = CURRENT.get();
        if (dataSource == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(dataSource);
        }
        return previous;
    }
}
