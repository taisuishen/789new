package com.bingo789.common.core.id;

/**
 * 64-bit time-ordered id: 41 bits millis since {@link #EPOCH} | 10 bits worker | 12 bits sequence.
 * <p>
 * The worker id MUST be unique per running instance. On CCE use the StatefulSet ordinal or a
 * lease from Redis/Nacos; never derive it from a pod IP hash in production.
 * JDK 24+ no longer pins virtual threads on {@code synchronized}, so a plain monitor is fine here.
 */
public class SnowflakeIdGenerator {

    /** 2025-01-01T00:00:00Z */
    public static final long EPOCH = 1735689600000L;

    private static final int WORKER_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    public static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_BACKWARD_MS = 5;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            long offset = lastTimestamp - now;
            if (offset > MAX_BACKWARD_MS) {
                throw new IllegalStateException("clock moved backwards by " + offset + "ms, refusing to generate id");
            }
            now = waitUntil(lastTimestamp);
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << (WORKER_BITS + SEQUENCE_BITS)) | (workerId << SEQUENCE_BITS) | sequence;
    }

    /** Extracts the creation time of an id; useful for partition pruning on time-partitioned tables. */
    public static long timestampOf(long id) {
        return (id >>> (WORKER_BITS + SEQUENCE_BITS)) + EPOCH;
    }

    private static long waitUntil(long target) {
        long now = System.currentTimeMillis();
        while (now < target) {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        }
        return now;
    }
}
