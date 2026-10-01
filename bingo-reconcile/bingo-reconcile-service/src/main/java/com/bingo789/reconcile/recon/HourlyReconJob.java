package com.bingo789.reconcile.recon;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.reconcile.config.ReconcileProperties;
import com.bingo789.reconcile.dw.ReconcileDw;
import com.bingo789.reconcile.entity.ReconLevel;
import com.bingo789.reconcile.model.ProviderTotals;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

/**
 * Layer 1: per provider and currency, net bet and net payout of the ledger vs the provider's bet history for
 * one UTC+8 hour, both summed by StarRocks (wallet_txn / provider_bet, exact under redelivery). Job param
 * (optional): the hour to reconcile, ISO local date-time in UTC+8, e.g. 2026-09-30T13:00.
 * Both sides are summed over all user lines: a player migrated between the bet and the provider pull is on
 * different lines on the two sides.
 * <p>
 * This layer is approximate by design: the ledger buckets by our transaction time, the provider by its bet time,
 * so rounds crossing an hour boundary (and provider clock skew) show up as small opposite diffs in adjacent hours.
 * Tolerance absorbs part of it; the daily per-record layer is authoritative.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HourlyReconJob {

    static final String NET_BET = "NET_BET";
    static final String NET_PAYOUT = "NET_PAYOUT";

    private final ReconcileDw dw;
    private final ReconDiffService diffService;
    private final ReconcileProperties properties;

    @XxlJob("reconHourlyJob")
    public void execute() {
        LocalDateTime hour;
        try {
            hour = targetHour(XxlJobHelper.getJobParam());
        } catch (DateTimeParseException e) {
            XxlJobHelper.handleFail("invalid hour parameter, expected e.g. 2026-09-30T13:00: " + e.getMessage());
            return;
        }
        Map<String, Totals> byKey = new TreeMap<>();
        for (ProviderTotals t : dw.platformTotals(hour, hour.plusHours(1))) {
            byKey.computeIfAbsent(key(t), k -> new Totals(t.getProviderCode(), t.getCurrency())).platform = t;
        }
        for (ProviderTotals t : dw.providerTotals(hour, hour.plusHours(1))) {
            byKey.computeIfAbsent(key(t), k -> new Totals(t.getProviderCode(), t.getCurrency())).provider = t;
        }

        int compared = 0;
        int diffs = 0;
        for (Totals totals : byKey.values()) {
            if (properties.excludedProviders().contains(totals.providerCode)) {
                continue;
            }
            compared++;
            if (diffService.compare(ReconLevel.HOURLY, totals.providerCode, totals.currency, hour, NET_BET,
                    bet(totals.platform), bet(totals.provider))) {
                diffs++;
            }
            if (diffService.compare(ReconLevel.HOURLY, totals.providerCode, totals.currency, hour, NET_PAYOUT,
                    payout(totals.platform), payout(totals.provider))) {
                diffs++;
            }
        }
        if (diffs > 0) {
            log.warn("hourly reconciliation {}: {} metrics outside tolerance across {} provider/currency pairs", hour, diffs, compared);
        }
        XxlJobHelper.log("hour={}, pairs={}, diffs={}", hour, compared, diffs);
    }

    private LocalDateTime targetHour(String param) {
        if (param != null && !param.isBlank()) {
            return LocalDateTime.parse(param.trim()).truncatedTo(ChronoUnit.HOURS);
        }
        return LocalDateTime.now(BingoTime.ZONE).minus(properties.hourlyLag()).truncatedTo(ChronoUnit.HOURS);
    }

    private static String key(ProviderTotals t) {
        return t.getProviderCode() + '|' + t.getCurrency();
    }

    private static BigDecimal bet(ProviderTotals t) {
        return t == null || t.getBetAmount() == null ? BigDecimal.ZERO : t.getBetAmount();
    }

    private static BigDecimal payout(ProviderTotals t) {
        return t == null || t.getPayoutAmount() == null ? BigDecimal.ZERO : t.getPayoutAmount();
    }

    private static final class Totals {
        private final String providerCode;
        private final String currency;
        private ProviderTotals platform;
        private ProviderTotals provider;

        private Totals(String providerCode, String currency) {
            this.providerCode = providerCode;
            this.currency = currency;
        }
    }
}
