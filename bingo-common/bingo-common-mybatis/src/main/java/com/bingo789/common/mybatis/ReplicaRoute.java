package com.bingo789.common.mybatis;

import java.util.function.Supplier;

/**
 * Lets the statements inside the scope go to a read-only node even when {@code bingo.mybatis.force-master=true}
 * (the wallet): for reads that tolerate replica lag, such as a player's transaction history. The TaurusDB proxy routes
 * SELECTs without the FORCE_MASTER hint to the read-only nodes. {@link MasterRoute} inside the scope still wins.
 */
public final class ReplicaRoute {

    private static final ThreadLocal<Boolean> ALLOWED = new ThreadLocal<>();

    private ReplicaRoute() {
    }

    public static <T> T run(Supplier<T> action) {
        Boolean previous = ALLOWED.get();
        ALLOWED.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ALLOWED.remove();
            } else {
                ALLOWED.set(previous);
            }
        }
    }

    public static boolean isAllowed() {
        return Boolean.TRUE.equals(ALLOWED.get());
    }
}
