package com.bingo789.risk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Amounts are in the platform currency (single-currency assumption).
 * TODO: per-currency thresholds once more than one currency is licensed.
 * The deposit play-through multiplier moved to bingo-turnover (turnover_setting, per user line).
 */
@ConfigurationProperties("bingo.risk")
public record RiskProperties(
        @DefaultValue Aml aml,
        @DefaultValue Turnover turnover,
        @DefaultValue Velocity velocity) {

    /**
     * Reporting thresholds are jurisdiction-specific and set by the licence conditions / AMLC rules.
     *
     * @param largeDepositThreshold defaults to the withdrawal threshold when not set
     */
    public record Aml(
            @DefaultValue("500000") BigDecimal largeWithdrawalThreshold,
            BigDecimal largeDepositThreshold) {

        public BigDecimal effectiveLargeDepositThreshold() {
            return largeDepositThreshold != null ? largeDepositThreshold : largeWithdrawalThreshold;
        }
    }

    /** @param rejectWhenOutstanding REJECT instead of REVIEW when a wagering requirement is still open */
    public record Turnover(@DefaultValue("false") boolean rejectWhenOutstanding) {
    }

    public record Velocity(@DefaultValue("3") int maxWithdrawalsPer24h) {
    }
}
