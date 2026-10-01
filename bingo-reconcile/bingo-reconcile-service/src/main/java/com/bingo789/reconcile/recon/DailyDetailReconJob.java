package com.bingo789.reconcile.recon;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.game.api.ProviderQueryClient;
import com.bingo789.game.api.dto.ResolveRoundCommand;
import com.bingo789.game.api.dto.RoundResolutionView;
import com.bingo789.reconcile.config.ReconcileProperties;
import com.bingo789.reconcile.dw.ReconcileDw;
import com.bingo789.reconcile.entity.DiffStatus;
import com.bingo789.reconcile.entity.ReconLevel;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Layer 2 (authoritative): per-record matching of ledger rounds against provider bet records for one reporting day.
 * Known mismatch patterns are compensated automatically, everything else becomes a recon_diff ticket
 * (one per provider, currency and pattern, with sample references in the note).
 * Job param (optional): yyyy-MM-dd; default yesterday in the reporting zone.
 * <p>
 * The matching runs in StarRocks (ReconcileDw#mismatchedRounds): billions of rows per day must not be joined on the
 * OLTP databases. Both sides are taken by the round's first bet time, so a round is compared as a whole even when it
 * settles after midnight. Providers without round ids in their bet history cannot be matched this way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyDetailReconJob {

    private static final int MAX_SAMPLES_IN_NOTE = 20;
    /** Beyond this many differing rounds a day the problem is systemic; the tickets carry what was read. */
    private static final int MAX_MISMATCHES = 100_000;

    private final ProviderQueryClient providerQueryClient;
    private final ReconDiffService diffService;
    private final ReconcileProperties properties;
    private final ReconcileDw dw;

    /** Mismatch patterns reported by the per-record matcher. */
    enum MismatchType {
        /** Provider shows a win we never credited: ask game-integration to resolve the round (applies the payout). */
        PROVIDER_WIN_NOT_CREDITED,
        /** Ledger has a bet the provider does not know: possible missed rollback, needs investigation. */
        MISSING_ON_PROVIDER,
        /** Provider has a bet our ledger never saw. */
        MISSING_ON_PLATFORM,
        AMOUNT_MISMATCH
    }

    record DetailMismatch(MismatchType type, String providerCode, String currency, String roundId, long userId,
                          BigDecimal platformAmount, BigDecimal providerAmount) {
    }

    private record GroupKey(String providerCode, String currency, MismatchType type) {
    }

    @XxlJob("reconDailyDetailJob")
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
        LocalDateTime periodStart = LocalDateTime.ofInstant(day.atStartOfDay(properties.reportZoneId()).toInstant(), BingoTime.ZONE);

        Map<GroupKey, List<DetailMismatch>> groups = findMismatches(periodStart, periodStart.plusDays(1)).stream()
                .collect(Collectors.groupingBy(m -> new GroupKey(m.providerCode(), m.currency(), m.type()),
                        LinkedHashMap::new, Collectors.toList()));
        int tickets = 0;
        for (Map.Entry<GroupKey, List<DetailMismatch>> entry : groups.entrySet()) {
            GroupKey key = entry.getKey();
            List<DetailMismatch> mismatches = entry.getValue();
            boolean allFixed = key.type() == MismatchType.PROVIDER_WIN_NOT_CREDITED
                    && mismatches.stream().map(this::compensate).reduce(true, Boolean::logicalAnd);
            BigDecimal platform = mismatches.stream().map(m -> nz(m.platformAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal provider = mismatches.stream().map(m -> nz(m.providerAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
            String note = mismatches.size() + " records, e.g. " + mismatches.stream().limit(MAX_SAMPLES_IN_NOTE)
                    .map(m -> m.roundId() + "/" + m.userId()).collect(Collectors.joining(","));
            diffService.ticket(ReconLevel.DAILY, key.providerCode(), key.currency(), periodStart, key.type().name(),
                    platform, provider, allFixed ? DiffStatus.AUTO_FIXED : DiffStatus.OPEN, note);
            tickets++;
        }
        XxlJobHelper.log("day={}, tickets={}", day, tickets);
    }

    /** Auto-compensation hook: game-integration re-queries the provider and applies the missing payout idempotently. */
    private boolean compensate(DetailMismatch mismatch) {
        try {
            RoundResolutionView resolution = providerQueryClient.resolveRound(new ResolveRoundCommand(
                    mismatch.providerCode(), mismatch.roundId(), mismatch.userId(), mismatch.currency()));
            return resolution != null && RoundResolutionView.SETTLED.equals(resolution.outcome());
        } catch (RuntimeException e) {
            log.warn("auto-compensation of round {}/{} failed: {}", mismatch.providerCode(), mismatch.roundId(), e.toString());
            return false;
        }
    }

    private List<DetailMismatch> findMismatches(LocalDateTime from, LocalDateTime to) {
        List<ReconcileDw.RoundPair> pairs = dw.mismatchedRounds(from, to, MAX_MISMATCHES);
        if (pairs.size() == MAX_MISMATCHES) {
            log.error("ALERT daily detail reconciliation {}: at least {} differing rounds, only these are ticketed", from, MAX_MISMATCHES);
        }
        return pairs.stream()
                .filter(p -> !properties.excludedProviders().contains(p.providerCode()))
                .map(DailyDetailReconJob::classify)
                .toList();
    }

    static DetailMismatch classify(ReconcileDw.RoundPair p) {
        BigDecimal platformNet = nz(p.platformBet()).subtract(nz(p.platformPayout()));
        BigDecimal providerNet = nz(p.providerBet()).subtract(nz(p.providerPayout()));
        MismatchType type;
        BigDecimal platformAmount = platformNet;
        BigDecimal providerAmount = providerNet;
        if (p.platformRounds() == 0) {
            type = MismatchType.MISSING_ON_PLATFORM;
        } else if (p.providerRecords() == 0) {
            type = MismatchType.MISSING_ON_PROVIDER;
        } else if (nz(p.platformBet()).compareTo(nz(p.providerBet())) == 0
                && nz(p.providerPayout()).compareTo(nz(p.platformPayout())) > 0) {
            type = MismatchType.PROVIDER_WIN_NOT_CREDITED;
            platformAmount = nz(p.platformPayout());
            providerAmount = nz(p.providerPayout());
        } else {
            type = MismatchType.AMOUNT_MISMATCH;
        }
        return new DetailMismatch(type, p.providerCode(), p.currency(), p.roundId(), p.userId(), platformAmount, providerAmount);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
