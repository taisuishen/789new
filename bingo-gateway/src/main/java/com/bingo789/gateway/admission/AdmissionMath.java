package com.bingo789.gateway.admission;

/** Pure waiting-room arithmetic. */
public final class AdmissionMath {

    /** Poll interval hints for queued clients (the status endpoint is served from memory, so polling is cheap). */
    static final int MIN_RETRY_AFTER_SECONDS = 2;
    static final int MAX_RETRY_AFTER_SECONDS = 30;

    private AdmissionMath() {
    }

    /**
     * How far the leader advances {@code admitted} this second:
     * {@code min(admitRatePerSecond, max(0, maxOnline - onlineEstimate), ticket - admitted)}.
     * <p>
     * At capacity the headroom is 0, so the queue only moves as players leave. Below capacity it drains at the
     * admit rate rather than jumping to {@code admitted = ticket} at once, so a large queue is not released in one
     * burst that overshoots capacity before the online estimate (which lags ~15s) catches up; a backlog that fits
     * into one second's budget catches up in a single step.
     *
     * @param onlineEstimate negative when unknown (Redis trouble): only the rate limit applies
     */
    public static long admitStep(long ticket, long admitted, long onlineEstimate, long maxOnline, int admitRatePerSecond) {
        long waiting = ticket - admitted;
        if (waiting <= 0 || admitRatePerSecond <= 0) {
            return 0;
        }
        long headroom = onlineEstimate < 0 ? Long.MAX_VALUE : Math.max(0, maxOnline - onlineEstimate);
        return Math.min(admitRatePerSecond, Math.min(headroom, waiting));
    }

    /** Players ahead of this ticket; 0 = may enter. */
    public static long position(long ticket, long admitted) {
        return Math.max(0, ticket - admitted);
    }

    /** When the client should poll again: the time to drain its position at the admit rate, clamped. */
    public static int retryAfterSeconds(long position, int admitRatePerSecond) {
        if (position <= 0) {
            return 1;
        }
        long seconds = admitRatePerSecond <= 0 ? MAX_RETRY_AFTER_SECONDS
                : (position + admitRatePerSecond - 1) / admitRatePerSecond;
        return Math.clamp(seconds, MIN_RETRY_AFTER_SECONDS, MAX_RETRY_AFTER_SECONDS);
    }
}
