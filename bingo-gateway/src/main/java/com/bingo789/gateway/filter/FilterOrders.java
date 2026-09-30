package com.bingo789.gateway.filter;

/**
 * Global filter order. All run before the route filters (default filters such as RequestRateLimiter get
 * orders 1, 2, ...) so that the rate limiter can key on the X-User-Id set by {@link AuthGlobalFilter}.
 * <p>
 * Degrade runs before auth so a disabled path costs no session lookup; admission runs after auth because it
 * needs the verified userId (online tracking, pass binding).
 */
public final class FilterOrders {

    public static final int TRACE_ID = -300;
    public static final int PATH_BLOCK = -250;
    public static final int GEO_FENCE = -200;
    public static final int DEGRADE = -150;
    public static final int AUTH = -100;
    public static final int ADMISSION = -90;

    private FilterOrders() {
    }
}
