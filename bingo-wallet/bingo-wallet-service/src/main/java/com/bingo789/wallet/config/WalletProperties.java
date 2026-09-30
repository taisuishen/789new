package com.bingo789.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param negativeBalancePolicy how to handle provider-driven debits that cannot be refused
 *                              (payout reversals, negative adjustments). Agree on this with every provider up front.
 */
@ConfigurationProperties("bingo.wallet")
public record WalletProperties(
        @DefaultValue("ALLOW") NegativeBalancePolicy negativeBalancePolicy,
        @DefaultValue Events events,
        @DefaultValue Retention retention) {

    public enum NegativeBalancePolicy {
        /** Apply the debit, the balance may go negative and is recovered from future credits. */
        ALLOW,
        /** Refuse with INSUFFICIENT_FUNDS; the provider must settle the difference offline. */
        REJECT
    }

    /** @param afterCommitPublish publish txn events after commit (local dev only; production uses Flink CDC) */
    public record Events(@DefaultValue("false") boolean afterCommitPublish) {
    }

    /**
     * wallet_txn retention (walletTxnRetentionJob): rows older than {@code keep} are deleted oldest-id-first in batches.
     * {@code keep} must exceed the longest provider retry / rollback window (a retry of a deleted transaction would be
     * booked again).
     */
    public record Retention(@DefaultValue("7d") Duration keep,
                            @DefaultValue("2000") int batchSize,
                            @DefaultValue("50ms") Duration pauseBetweenBatches,
                            @DefaultValue("30m") Duration maxRunTime) {
    }

    public boolean allowNegativeBalance() {
        return negativeBalancePolicy == NegativeBalancePolicy.ALLOW;
    }
}
