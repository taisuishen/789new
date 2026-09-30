package com.bingo789.gateway.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class AdmissionMetrics {

    private AdmissionMetrics() {
    }

    /** Redis failures in the waiting room; every one of them resolves to "admit". */
    static Counter redisFailures(MeterRegistry registry, String operation) {
        return Counter.builder("bingo.gateway.admission.redis.failures")
                .description("Waiting-room Redis failures (admission fails open)")
                .tag("op", operation)
                .register(registry);
    }
}
