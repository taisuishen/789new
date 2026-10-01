package com.bingo789.common.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes this instance out of service discovery at the START of a pod shutdown, while it still serves requests.
 * <p>
 * The pod's preStop hook creates the marker file and then sleeps ({@code touch /tmp/draining; sleep 15}). Within a
 * second this watcher de-registers the instance from Nacos; callers stop picking it after the Nacos push plus their
 * load-balancer cache ttl (5s, bingo-common.yaml), well inside the sleep. Only then does SIGTERM start Spring's
 * graceful shutdown. Without this the instance stays registered until the context closes, i.e. callers keep sending
 * it requests while it is already refusing them.
 * <p>
 * The marker is deleted on start: /tmp is an emptyDir that survives a container restart inside the same pod.
 */
@Slf4j
public class RegistryDrain implements SmartLifecycle {

    private final Path marker;
    private final ObjectProvider<ServiceRegistry<Registration>> registry;
    private final ObjectProvider<Registration> registration;
    private final AtomicBoolean drained = new AtomicBoolean();
    private volatile ScheduledExecutorService timer;

    public RegistryDrain(Path marker, ObjectProvider<ServiceRegistry<Registration>> registry,
                         ObjectProvider<Registration> registration) {
        this.marker = marker;
        this.registry = registry;
        this.registration = registration;
    }

    @Override
    public void start() {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            log.warn("cannot delete drain marker {}: {}", marker, e.toString());
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("registry-drain").factory());
        executor.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS);
        timer = executor;
    }

    private void check() {
        if (drained.get() || !Files.exists(marker)) {
            return;
        }
        Registration self = registration.getIfAvailable();
        ServiceRegistry<Registration> nacos = registry.getIfAvailable();
        if (self == null || nacos == null || !drained.compareAndSet(false, true)) {
            return;
        }
        try {
            nacos.deregister(self);
            log.info("drain marker {} found: de-registered {} from service discovery", marker, self.getServiceId());
        } catch (RuntimeException e) {
            log.warn("de-registration on drain failed (the context close retries it): {}", e.toString());
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor = timer;
        if (executor != null) {
            executor.shutdownNow();
            timer = null;
        }
    }

    @Override
    public boolean isRunning() {
        return timer != null;
    }
}
