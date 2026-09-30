package com.bingo789.gateway.admission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Applies {@code max-online} and {@code admit-rate-per-second} changes from Nacos without a restart, so ops can
 * throttle entry during an incident (docs/capacity-1m.md §5: lower the rate first, max-online last). Other admission
 * settings (paths, TTLs, secret) still need a rolling restart. Invalid values are rejected and the previous limits
 * stay active.
 */
@Slf4j
@Component
public class AdmissionLimitsRefresher {

    static final String MAX_ONLINE = "bingo.gateway.admission.max-online";
    static final String ADMIT_RATE = "bingo.gateway.admission.admit-rate-per-second";

    private final WaitingRoom waitingRoom;
    private final Environment environment;

    public AdmissionLimitsRefresher(WaitingRoom waitingRoom, Environment environment) {
        this.waitingRoom = waitingRoom;
        this.environment = environment;
    }

    /** Cheap enough to re-bind on every change event, whichever keys changed. */
    @EventListener
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        if (!waitingRoom.enabled()) {
            return;
        }
        long maxOnline;
        int rate;
        try {
            Binder binder = Binder.get(environment);
            maxOnline = binder.bind(MAX_ONLINE, Long.class).orElse(waitingRoom.maxOnline());
            rate = binder.bind(ADMIT_RATE, Integer.class).orElse(waitingRoom.admitRatePerSecond());
            if (maxOnline == waitingRoom.maxOnline() && rate == waitingRoom.admitRatePerSecond()) {
                return;
            }
            waitingRoom.updateLimits(maxOnline, rate);
        } catch (RuntimeException e) {
            log.error("invalid admission limits, keeping max-online={} admit-rate-per-second={}",
                    waitingRoom.maxOnline(), waitingRoom.admitRatePerSecond(), e);
            return;
        }
        log.warn("admission limits changed: max-online={}, admit-rate-per-second={}", maxOnline, rate);
    }
}
