package com.bingo789.common.mybatis;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import org.apache.ibatis.executor.statement.StatementHandler;

import java.sql.Connection;

/**
 * Prefixes SQL with the TaurusDB proxy hint so the statement is executed on the primary node.
 * <p>
 * TaurusDB read replicas share storage with the primary but still lag on redo metadata, so the
 * wallet (and any read-after-write path) must never read from a replica.
 */
public class ForceMasterInnerInterceptor implements InnerInterceptor {

    private final boolean always;
    private final String hint;

    public ForceMasterInnerInterceptor(boolean always, String hint) {
        this.always = always;
        this.hint = hint.endsWith(" ") ? hint : hint + " ";
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        if (!MasterRoute.isForced() && (!always || ReplicaRoute.isAllowed())) {
            return;
        }
        PluginUtils.MPBoundSql boundSql = PluginUtils.mpStatementHandler(sh).mPBoundSql();
        String sql = boundSql.sql();
        if (!sql.startsWith(hint)) {
            boundSql.sql(hint + sql);
        }
    }
}
