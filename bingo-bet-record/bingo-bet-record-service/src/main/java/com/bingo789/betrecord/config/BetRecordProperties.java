package com.bingo789.betrecord.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * @param unsettledThreshold           an OPEN round without events for this long is handed to the resolver
 * @param unsettledThresholdByProvider per-provider override (live tables / tournaments legitimately stay open longer)
 * @param maxResolveAttempts           resolver attempts after which every further attempt raises an alert
 * @param batchSize                    page size of the resolver and republish scans
 * @param roundLookbackDays            how many days before the event date the open round is searched (partition pruning)
 * @param lateEventLookbackDays        wider search for settlements/reversals whose round is older than that; keep it
 *                                     equal to the wallet's idempotency window (30 days) so every late transaction the
 *                                     wallet accepts revises its round
 * @param republishDelay               a terminal round whose event has not been acknowledged for this long is re-sent
 * @param partitionKeepMonths          full months of game_round / provider_bet_record / round_txn kept before the
 *                                     current one (betRecordPartitionJob)
 */
@ConfigurationProperties("bingo.bet-record")
public record BetRecordProperties(
        @DefaultValue("30m") Duration unsettledThreshold,
        Map<String, Duration> unsettledThresholdByProvider,
        @DefaultValue("12") int maxResolveAttempts,
        @DefaultValue("200") int batchSize,
        @DefaultValue("1") int roundLookbackDays,
        @DefaultValue("30") int lateEventLookbackDays,
        @DefaultValue("2m") Duration republishDelay,
        @DefaultValue Pull pull,
        @DefaultValue("2") int partitionKeepMonths) {

    public BetRecordProperties {
        unsettledThresholdByProvider = unsettledThresholdByProvider == null ? Map.of() : Map.copyOf(unsettledThresholdByProvider);
    }

    public Duration unsettledThresholdFor(String providerCode) {
        return unsettledThresholdByProvider.getOrDefault(providerCode, unsettledThreshold);
    }

    /** Smallest threshold of all providers: the database scan uses it, the per-provider check happens in Java. */
    public Duration minUnsettledThreshold() {
        return unsettledThresholdByProvider.values().stream()
                .reduce(unsettledThreshold, (a, b) -> a.compareTo(b) <= 0 ? a : b);
    }

    /**
     * Provider bet-history pull.
     *
     * @param overlap      each window starts this much before the previous window end, so late-indexed records are not missed
     * @param settleDelay  the window never reaches closer to "now" than this (providers index records with a delay)
     * @param maxWindow    upper bound of one run's window (catch-up after downtime happens in steps)
     * @param pageInterval pause between pages to stay under the provider's rate limit
     * @param maxPages     safety stop for a provider that keeps returning hasMore
     * @param providers    per provider code, values that differ from the defaults above (unset = default), e.g. a
     *                     longer settle delay for a provider that writes its history late, or a longer page interval
     *                     for one limited to 1 request per second
     */
    public record Pull(
            @DefaultValue("5m") Duration overlap,
            @DefaultValue("2m") Duration settleDelay,
            @DefaultValue("30m") Duration maxWindow,
            @DefaultValue("500") int pageSize,
            @DefaultValue("200ms") Duration pageInterval,
            @DefaultValue("2000") int maxPages,
            @DefaultValue Map<String, ProviderPull> providers) {

        public Pull {
            providers = providers == null ? Map.of() : Map.copyOf(providers);
        }

        /** The effective settings of one provider. */
        public Pull forProvider(String providerCode) {
            ProviderPull o = providers.get(providerCode);
            if (o == null) {
                return this;
            }
            return new Pull(o.overlap() != null ? o.overlap() : overlap,
                    o.settleDelay() != null ? o.settleDelay() : settleDelay,
                    o.maxWindow() != null ? o.maxWindow() : maxWindow,
                    o.pageSize() != null ? o.pageSize() : pageSize,
                    o.pageInterval() != null ? o.pageInterval() : pageInterval,
                    maxPages, Map.of());
        }
    }

    /** Per-provider override of {@link Pull}; null fields keep the default. */
    public record ProviderPull(Duration overlap, Duration settleDelay, Duration maxWindow, Integer pageSize,
                               Duration pageInterval) {
    }
}
