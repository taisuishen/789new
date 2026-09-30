package com.bingo789.reconcile.recon;

import com.bingo789.reconcile.config.ReconcileProperties;
import com.bingo789.reconcile.entity.DiffStatus;
import com.bingo789.reconcile.entity.ReconLevel;
import com.bingo789.reconcile.mapper.ReconDiffMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Writes recon_diff tickets; re-runs of a period are idempotent (unique level/provider/currency/period/metric). */
@Service
@RequiredArgsConstructor
public class ReconDiffService {

    private static final int MAX_NOTE_LENGTH = 500;

    private final ReconDiffMapper mapper;
    private final ReconcileProperties properties;

    /**
     * Compares one metric. Outside tolerance an OPEN ticket is created (or refreshed); within tolerance an OPEN
     * ticket from an earlier run of the same period is closed as AUTO_FIXED.
     *
     * @return true when the metric is outside tolerance
     */
    public boolean compare(ReconLevel level, String providerCode, String currency, LocalDateTime periodStart,
                           String metric, BigDecimal platformValue, BigDecimal providerValue) {
        BigDecimal diff = platformValue.subtract(providerValue);
        if (diff.abs().compareTo(properties.tolerance()) > 0) {
            mapper.upsert(level.name(), providerCode, currency, periodStart, metric, platformValue, providerValue, diff,
                    DiffStatus.OPEN.name(), null);
            return true;
        }
        mapper.autoClose(level.name(), providerCode, currency, periodStart, metric, platformValue, providerValue, diff,
                "matched on re-run");
        return false;
    }

    /** Ticket produced by the per-record layer (status OPEN, or AUTO_FIXED when compensated automatically). */
    public void ticket(ReconLevel level, String providerCode, String currency, LocalDateTime periodStart, String metric,
                       BigDecimal platformValue, BigDecimal providerValue, DiffStatus status, String note) {
        mapper.upsert(level.name(), providerCode, currency, periodStart, metric, platformValue, providerValue,
                platformValue.subtract(providerValue), status.name(), truncate(note));
    }

    private static String truncate(String note) {
        return note == null || note.length() <= MAX_NOTE_LENGTH ? note : note.substring(0, MAX_NOTE_LENGTH);
    }
}
