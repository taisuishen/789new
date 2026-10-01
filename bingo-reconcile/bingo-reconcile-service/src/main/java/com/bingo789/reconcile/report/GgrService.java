package com.bingo789.reconcile.report;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.reconcile.config.ReconcileProperties;
import com.bingo789.reconcile.dw.ReconcileDw;
import com.bingo789.reconcile.mapper.GgrDailyMapper;
import com.bingo789.reconcile.model.GgrRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GgrService {

    private static final int INSERT_BATCH = 500;

    private final GgrDailyMapper ggrMapper;
    private final ReconcileDw dw;
    private final TransactionTemplate transactionTemplate;
    private final ReconcileProperties properties;

    /**
     * Rebuilds one reporting day: the day's net figures are summed in StarRocks (outside any transaction), then
     * ggr_daily's rows of that day are replaced in one local transaction, so a re-run after late events or a
     * correction always yields the full, current picture.
     *
     * @return rows written
     */
    public int rebuildDay(LocalDate day) {
        ZoneId zone = properties.reportZoneId();
        LocalDateTime from = LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(), BingoTime.ZONE);
        LocalDateTime to = LocalDateTime.ofInstant(day.plusDays(1).atStartOfDay(zone).toInstant(), BingoTime.ZONE);
        List<GgrRow> rows = dw.ggrRows(from, to);
        return transactionTemplate.execute(tx -> {
            ggrMapper.deleteDay(day);
            int written = 0;
            for (int i = 0; i < rows.size(); i += INSERT_BATCH) {
                written += ggrMapper.insertDay(day, rows.subList(i, Math.min(rows.size(), i + INSERT_BATCH)));
            }
            return written;
        });
    }
}
