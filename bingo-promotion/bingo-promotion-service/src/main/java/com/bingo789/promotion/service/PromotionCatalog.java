package com.bingo789.promotion.service;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.PromotionEntry;
import com.bingo789.promotion.mapper.PromotionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Node-local snapshot of the ONLINE promotions that have not ended yet, for the player-facing activity list.
 * The database is the source of truth: every pod reloads the whole (small) set every {@code catalog-refresh-interval},
 * so a change made through any pod shows everywhere within that interval. A failed reload keeps the previous
 * snapshot; the time window is checked on every read, so an ended promotion disappears on time regardless.
 * Money decisions (first-deposit bonus, rebates) query the table directly instead of this snapshot.
 */
@Slf4j
@Component
public class PromotionCatalog implements SmartLifecycle {

    private final PromotionMapper promotionMapper;
    private final Duration refreshInterval;
    private volatile List<PromotionEntry> entries;
    private ScheduledExecutorService scheduler;

    public PromotionCatalog(PromotionMapper promotionMapper, PromotionProperties properties) {
        this.promotionMapper = promotionMapper;
        this.refreshInterval = properties.catalogRefreshInterval();
    }

    /** ONLINE, not yet ended promotions, highest priority first; loaded on first use if no refresh ran yet. */
    public List<PromotionEntry> entries() {
        List<PromotionEntry> current = entries;
        if (current == null) {
            synchronized (this) {
                if (entries == null) {
                    refresh();
                }
                current = entries;
            }
        }
        return current;
    }

    /** Reloads the snapshot; a malformed row is left out (and alerted) rather than failing the whole catalog. */
    public void refresh() {
        List<Promotion> rows = promotionMapper.selectOnlineNotEnded(BingoTime.now());
        List<PromotionEntry> loaded = new ArrayList<>(rows.size());
        for (Promotion row : rows) {
            try {
                loaded.add(PromotionEntry.of(row));
            } catch (RuntimeException e) {
                log.error("ALERT promotion {} is malformed and left out of the catalog", row.getId(), e);
            }
        }
        loaded.sort(PromotionEntry.PRIORITY);
        entries = List.copyOf(loaded);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("promotion catalog refresh failed, keeping the previous snapshot", e);
        }
    }

    @Override
    public void start() {
        refreshQuietly();
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("promotion-catalog").daemon(true).factory());
        long intervalMs = refreshInterval.toMillis();
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }
}
