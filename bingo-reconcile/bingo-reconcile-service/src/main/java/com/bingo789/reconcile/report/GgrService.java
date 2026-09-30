package com.bingo789.reconcile.report;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.reconcile.config.ReconcileProperties;
import com.bingo789.reconcile.mapper.GgrDailyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class GgrService {

    private final GgrDailyMapper ggrMapper;
    private final ReconcileProperties properties;

    /**
     * Rebuilds one reporting day from the hourly ledger aggregates (delete + insert-select in one transaction),
     * so a re-run after late events or a correction always yields the full, current picture.
     *
     * @return rows written
     */
    @Transactional
    public int rebuildDay(LocalDate day) {
        ZoneId zone = properties.reportZoneId();
        LocalDateTime from = LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(), BingoTime.ZONE);
        LocalDateTime to = LocalDateTime.ofInstant(day.plusDays(1).atStartOfDay(zone).toInstant(), BingoTime.ZONE);
        ggrMapper.deleteDay(day);
        return ggrMapper.insertDayFromHourly(day, from, to);
    }
}
