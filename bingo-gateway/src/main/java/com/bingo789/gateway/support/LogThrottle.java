package com.bingo789.gateway.support;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** At most one "go" per interval, for warnings that could otherwise fire per request during an outage. */
public final class LogThrottle {

    private final long intervalNanos;
    private final AtomicLong next = new AtomicLong(System.nanoTime());

    public LogThrottle(Duration interval) {
        this.intervalNanos = interval.toNanos();
    }

    public boolean tryAcquire() {
        long now = System.nanoTime();
        long due = next.get();
        return now - due >= 0 && next.compareAndSet(due, now + intervalNanos);
    }
}
