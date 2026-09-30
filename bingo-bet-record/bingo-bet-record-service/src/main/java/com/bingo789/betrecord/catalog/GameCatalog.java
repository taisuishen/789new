package com.bingo789.betrecord.catalog;

import com.bingo789.lobby.api.LobbyClient;
import com.bingo789.lobby.api.dto.GameView;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Game type and name per provider game, cached in memory from the lobby catalogue ({@link LobbyClient#providerGames}).
 * <p>
 * A provider's catalogue older than {@link #REFRESH_INTERVAL} is reloaded in the background while lookups keep being
 * served from the cached copy. Only a game code missing from the cache (first use of a provider, or a game added
 * since the last load) loads the catalogue synchronously, so the first round of a new game already gets its type.
 * Whatever triggers it, a provider's catalogue is requested at most once per {@link #RETRY_INTERVAL}.
 * <p>
 * A lobby outage never fails the caller (round projection must go on): the cached values, or
 * {@link GameInfo#unknown}, are returned and the failed load is logged at WARN, at most once per provider and
 * retry interval.
 */
@Slf4j
@Component
public class GameCatalog {

    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    static final Duration RETRY_INTERVAL = Duration.ofMinutes(1);

    private final LobbyClient lobbyClient;
    private final LongSupplier nanoClock;
    private final Executor refreshExecutor;
    private final ConcurrentMap<String, ProviderGames> providers = new ConcurrentHashMap<>();

    @Autowired
    public GameCatalog(LobbyClient lobbyClient) {
        this(lobbyClient, System::nanoTime, Executors.newVirtualThreadPerTaskExecutor());
    }

    GameCatalog(LobbyClient lobbyClient, LongSupplier nanoClock, Executor refreshExecutor) {
        this.lobbyClient = lobbyClient;
        this.nanoClock = nanoClock;
        this.refreshExecutor = refreshExecutor;
    }

    /** Never throws and never returns null. */
    public GameInfo lookup(String providerCode, String gameCode) {
        if (providerCode == null || gameCode == null || gameCode.isEmpty()) {
            return GameInfo.unknown(gameCode);
        }
        ProviderGames provider = providers.computeIfAbsent(providerCode, ProviderGames::new);
        GameInfo info = provider.games.get(gameCode);
        if (info == null) {
            // waits for a load of the same provider already in flight instead of recording OTHER meanwhile
            load(provider);
            info = provider.games.get(gameCode);
        } else {
            long now = nanoClock.getAsLong();
            if (provider.isStale(now) && provider.mayLoad(now)) {
                refreshInBackground(provider);
            }
        }
        return info != null ? info : GameInfo.unknown(gameCode);
    }

    private void refreshInBackground(ProviderGames provider) {
        if (!provider.refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    load(provider);
                } finally {
                    provider.refreshing.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            // shutting down; the cached copy stays in use
            provider.refreshing.set(false);
        }
    }

    /** One load at a time per provider; a caller that waited for a concurrent load reuses its result. */
    private void load(ProviderGames provider) {
        synchronized (provider) {
            long now = nanoClock.getAsLong();
            if (!provider.mayLoad(now)) {
                return;
            }
            provider.attempted = true;
            provider.attemptedAt = now;
            try {
                List<GameView> games = lobbyClient.providerGames(provider.providerCode);
                if (games == null) {
                    throw new IllegalStateException("lobby returned no catalogue");
                }
                Map<String, GameInfo> byCode = new HashMap<>();
                for (GameView game : games) {
                    if (game != null && game.gameCode() != null) {
                        byCode.putIfAbsent(game.gameCode(), GameInfo.of(game));
                    }
                }
                provider.games = Map.copyOf(byCode);
                provider.loadedAt = now;
            } catch (RuntimeException e) {
                log.warn("game catalogue of provider {} could not be loaded from the lobby, keeping {} cached games "
                                + "(unknown games are recorded as OTHER); next attempt in {}: {}",
                        provider.providerCode, provider.games.size(), RETRY_INTERVAL, e.toString());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (refreshExecutor instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    private static final class ProviderGames {

        private final String providerCode;
        private final AtomicBoolean refreshing = new AtomicBoolean();
        private volatile Map<String, GameInfo> games = Map.of();
        private volatile boolean attempted;
        /** System.nanoTime of the last load attempt / successful load. */
        private volatile long attemptedAt;
        private volatile long loadedAt;

        private ProviderGames(String providerCode) {
            this.providerCode = providerCode;
        }

        /** At most one lobby request per provider and retry interval, whatever triggers it. */
        boolean mayLoad(long now) {
            return !attempted || now - attemptedAt >= RETRY_INTERVAL.toNanos();
        }

        boolean isStale(long now) {
            return now - loadedAt >= REFRESH_INTERVAL.toNanos();
        }
    }
}
