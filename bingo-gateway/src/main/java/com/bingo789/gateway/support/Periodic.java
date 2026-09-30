package com.bingo789.gateway.support;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.function.Supplier;

/** Non-blocking fixed-rate background task: ticks that arrive while the previous run is still busy are dropped. */
@Slf4j
public final class Periodic {

    private Periodic() {
    }

    /**
     * @param timeout per run, so one hung Redis call cannot stall the task forever
     * @return dispose to stop
     */
    public static Disposable every(String name, Duration period, Duration timeout, Supplier<Mono<Void>> task) {
        return Flux.interval(period, Schedulers.parallel())
                .onBackpressureDrop()
                .concatMap(tick -> Mono.defer(task)
                        // Spring Data Redis opens its shared Lettuce connection synchronously until the first
                        // success; keep that off the parallel scheduler (one hop per run, negligible).
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(timeout)
                        .onErrorResume(e -> {
                            log.warn("{} failed: {}", name, e.toString());
                            return Mono.empty();
                        }), 1)
                .subscribe();
    }
}
