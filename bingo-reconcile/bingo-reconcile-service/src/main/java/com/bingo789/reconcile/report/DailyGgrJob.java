package com.bingo789.reconcile.report;

import com.bingo789.reconcile.config.ReconcileProperties;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Builds ggr_daily (GGR = net bet - net payout) for one reporting day from StarRocks wallet_txn.
 * Job param (optional): yyyy-MM-dd; default yesterday in the reporting zone. Schedule it a few hours after
 * midnight of the reporting zone so the last hours' ledger events have been loaded (Routine Load lag: seconds).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyGgrJob {

    private final GgrService ggrService;
    private final ReconcileProperties properties;

    @XxlJob("reconDailyGgrJob")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        LocalDate day;
        try {
            day = param == null || param.isBlank()
                    ? LocalDate.now(properties.reportZoneId()).minusDays(1)
                    : LocalDate.parse(param.trim());
        } catch (DateTimeParseException e) {
            XxlJobHelper.handleFail("invalid date parameter, expected yyyy-MM-dd: " + e.getMessage());
            return;
        }
        int rows = ggrService.rebuildDay(day);
        log.info("ggr_daily rebuilt for {}: {} rows", day, rows);
        XxlJobHelper.log("day={}, rows={}", day, rows);
    }
}
