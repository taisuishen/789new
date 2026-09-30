package com.bingo789.common.core;

/**
 * HTTP headers propagated from the gateway to downstream services.
 * The gateway strips any client-supplied copies of these before setting its own values.
 */
public final class HeaderNames {

    public static final String TRACE_ID = "X-Trace-Id";
    public static final String USER_ID = "X-User-Id";
    public static final String SESSION_ID = "X-Session-Id";
    public static final String CLIENT_IP = "X-Client-Ip";
    public static final String DEVICE_ID = "X-Device-Id";
    /** Country resolved by WAF/CDN from the client IP; used for geo-fencing. */
    public static final String COUNTRY_CODE = "X-Country-Code";
    /** Back-office operator, set by the back-office gateway after operator authentication. */
    public static final String OPERATOR_ID = "X-Operator-Id";

    private HeaderNames() {
    }
}
