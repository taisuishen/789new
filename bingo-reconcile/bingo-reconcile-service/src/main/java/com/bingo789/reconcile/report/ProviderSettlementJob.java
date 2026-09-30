package com.bingo789.reconcile.report;

import com.bingo789.reconcile.config.ReconcileProperties;
import com.bingo789.reconcile.mapper.GgrDailyMapper;
import com.bingo789.reconcile.mapper.ProviderSettlementMapper;
import com.bingo789.reconcile.model.GgrRow;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Monthly revenue-share statement per provider and currency: amount_due = GGR x rate (bingo.reconcile.revenue-share).
 * Job param (optional): yyyy-MM; default the previous month in the reporting zone. Statements stay DRAFT (and are
 * recomputed on re-runs) until finance confirms them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderSettlementJob {

    private final GgrDailyMapper ggrMapper;
    private final ProviderSettlementMapper settlementMapper;
    private final ReconcileProperties properties;

    @XxlJob("reconProviderSettlementJob")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        YearMonth period;
        try {
            period = param == null || param.isBlank()
                    ? YearMonth.now(properties.reportZoneId()).minusMonths(1)
                    : YearMonth.parse(param.trim());
        } catch (DateTimeParseException e) {
            XxlJobHelper.handleFail("invalid period parameter, expected yyyy-MM: " + e.getMessage());
            return;
        }
        LocalDate from = period.atDay(1);
        LocalDate to = period.plusMonths(1).atDay(1);
        List<GgrRow> rows = ggrMapper.sumByProviderCurrency(from, to);
        Set<String> missingRates = new TreeSet<>();
        int written = 0;
        for (GgrRow row : rows) {
            BigDecimal rate = properties.revenueShare().get(row.getProviderCode());
            if (rate == null) {
                missingRates.add(row.getProviderCode());
                continue;
            }
            BigDecimal ggr = row.getGgr() == null ? BigDecimal.ZERO : row.getGgr();
            // TODO: negative-GGR carry-over and minimum fees per provider contract; a negative month is due 0 for now
            BigDecimal amountDue = ggr.signum() > 0
                    ? ggr.multiply(rate).setScale(4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(4);
            settlementMapper.upsertDraft(period.toString(), row.getProviderCode(), row.getCurrency(), ggr, rate, amountDue);
            written++;
        }
        XxlJobHelper.log("period={}, statements={}", period, written);
        if (!missingRates.isEmpty()) {
            log.warn("no revenue-share rate configured for providers {} (period {})", missingRates, period);
            XxlJobHelper.handleFail("missing bingo.reconcile.revenue-share for " + missingRates);
        }
    }
}
