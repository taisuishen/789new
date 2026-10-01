package com.bingo789.reconcile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/**
 * @param tolerance         absolute difference (per provider, currency and metric) below which platform and provider agree
 * @param hourlyLag         the hourly job reconciles the hour that ended this long ago, so late events can arrive
 * @param reportZone        zone of the regulatory reporting day (GGR, settlements); must have a whole-hour offset
 *                          because aggregates are hourly
 * @param revenueShare      provider code -> revenue share rate (0.12 = 12% of GGR)
 * @param excludedProviders providers whose game transactions never reach our ledger (transfer-wallet mode);
 *                          comparing them bet-by-bet would raise a diff every hour
 * @param dw                StarRocks (bingo_dw), the only source of the figures compared and reported here
 */
@ConfigurationProperties("bingo.reconcile")
public record ReconcileProperties(
        @DefaultValue("0") BigDecimal tolerance,
        @DefaultValue("2h") Duration hourlyLag,
        @DefaultValue("+08:00") String reportZone,
        Map<String, BigDecimal> revenueShare,
        Set<String> excludedProviders,
        @DefaultValue Rtp rtp,
        @DefaultValue Dw dw) {

    public ReconcileProperties {
        revenueShare = revenueShare == null ? Map.of() : Map.copyOf(revenueShare);
        excludedProviders = excludedProviders == null ? Set.of() : Set.copyOf(excludedProviders);
        if (ZoneId.of(reportZone).getRules().getOffset(Instant.now()).getTotalSeconds() % 3600 != 0) {
            throw new IllegalArgumentException("bingo.reconcile.report-zone must have a whole-hour UTC offset: " + reportZone);
        }
    }

    public ZoneId reportZoneId() {
        return ZoneId.of(reportZone);
    }

    /**
     * StarRocks FE over the MySQL protocol (port 9030), read-only account.
     *
     * @param url      e.g. jdbc:mysql://fe-host:9030/bingo_dw?connectTimeout=3000&socketTimeout=600000
     * @param poolSize the jobs run one at a time per pod; a few connections are plenty
     */
    public record Dw(String url, String username, String password, @DefaultValue("4") int poolSize) {
    }

    /**
     * RTP monitor. Slot RTP converges slowly (high-volatility games swing several points over millions of spins),
     * so the volume thresholds must be high enough for the deviation to mean something.
     *
     * @param windowDays   full reporting days evaluated
     * @param minRounds    minimum stakes (bet transactions) in the window
     * @param minBet       minimum net bet in the window, in currency units
     * @param maxDeviation alert when |actual - theoretical| exceeds this many percentage points
     */
    public record Rtp(
            @DefaultValue("7") int windowDays,
            @DefaultValue("10000") long minRounds,
            @DefaultValue("100000") BigDecimal minBet,
            @DefaultValue("3.0") BigDecimal maxDeviation) {
    }
}
