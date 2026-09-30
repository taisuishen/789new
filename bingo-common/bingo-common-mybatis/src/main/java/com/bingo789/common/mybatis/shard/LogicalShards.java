package com.bingo789.common.mybatis.shard;

/**
 * user id -> logical shard. The function and the shard count are part of the data layout: changing either one
 * moves every user, so they must never change without a full migration.
 * <p>
 * User ids are snowflakes whose low bits (sequence) are mostly zero, so {@code id % n} would be badly skewed;
 * the SplitMix64 finalizer spreads them evenly first.
 */
public final class LogicalShards {

    private LogicalShards() {
    }

    public static int of(long userId, int logicalShards) {
        long z = userId + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (int) Math.floorMod(z, (long) logicalShards);
    }
}
