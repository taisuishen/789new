package com.bingo789.common.core.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Trace id held in the SLF4J MDC. When the OpenTelemetry / SkyWalking agent is attached,
 * the web filter prefers the agent's trace id so logs and traces correlate.
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
