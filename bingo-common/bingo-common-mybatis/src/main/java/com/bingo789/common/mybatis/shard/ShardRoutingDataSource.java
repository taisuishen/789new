package com.bingo789.common.mybatis.shard;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;

/**
 * Picks the physical datasource from {@link ShardContext}. Strict: data access outside a shard scope is a bug
 * (it would silently hit an arbitrary database), so it fails instead of falling back to a default.
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource implements DisposableBean {

    @Override
    protected Object determineCurrentLookupKey() {
        String dataSource = ShardContext.current();
        if (dataSource == null) {
            throw new IllegalStateException("no shard selected: wrap data access in ShardTemplate.forUser / onGlobal / forEachDataSource");
        }
        return dataSource;
    }

    /** The per-shard pools are not beans of their own, so close them with the router. */
    @Override
    public void destroy() throws Exception {
        for (DataSource target : getResolvedDataSources().values()) {
            if (target instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
