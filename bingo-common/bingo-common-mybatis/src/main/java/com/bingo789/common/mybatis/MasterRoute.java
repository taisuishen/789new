package com.bingo789.common.mybatis;

import java.util.function.Supplier;

/**
 * Forces statements executed inside {@link #run} onto the primary node, for read-your-writes
 * in services whose default reads go to TaurusDB read replicas.
 */
public final class MasterRoute {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private MasterRoute() {
    }

    public static <T> T run(Supplier<T> action) {
        DEPTH.set(DEPTH.get() + 1);
        try {
            return action.get();
        } finally {
            int depth = DEPTH.get() - 1;
            if (depth == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(depth);
            }
        }
    }

    public static boolean isForced() {
        return DEPTH.get() > 0;
    }
}
