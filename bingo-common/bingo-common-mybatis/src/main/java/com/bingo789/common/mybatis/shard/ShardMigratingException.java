package com.bingo789.common.mybatis.shard;

import com.bingo789.common.core.RetryableException;

/** Writes of this user's logical shard are paused while it moves to another database. Always retryable. */
public class ShardMigratingException extends RetryableException {

    public ShardMigratingException(int logicalShard) {
        super("logical shard " + logicalShard + " is being migrated, retry shortly");
    }
}
