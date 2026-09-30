package com.bingo789.reconcile.model;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** In-batch delta for one recon_platform_hourly row (all game-transaction amounts are non-negative except adjust). */
@Getter
public class PlatformHourlyRow {

    private final LocalDateTime statHour;
    private final int userLine;
    private final String providerCode;
    private final String gameCode;
    private final String currency;
    private BigDecimal betAmount = BigDecimal.ZERO;
    private BigDecimal rollbackAmount = BigDecimal.ZERO;
    private BigDecimal payoutAmount = BigDecimal.ZERO;
    private BigDecimal payoutReversalAmount = BigDecimal.ZERO;
    /** Signed: +credit to the player, -debit. */
    private BigDecimal adjustAmount = BigDecimal.ZERO;
    private long betCount;
    private long txnCount;

    public PlatformHourlyRow(HourKey key) {
        this.statHour = key.statHour();
        this.userLine = key.userLine();
        this.providerCode = key.providerCode();
        this.gameCode = key.gameCode();
        this.currency = key.currency();
    }

    public void addBet(BigDecimal amount) {
        betAmount = betAmount.add(amount);
        betCount++;
        txnCount++;
    }

    public void addRollback(BigDecimal amount) {
        rollbackAmount = rollbackAmount.add(amount);
        txnCount++;
    }

    public void addPayout(BigDecimal amount) {
        payoutAmount = payoutAmount.add(amount);
        txnCount++;
    }

    public void addPayoutReversal(BigDecimal amount) {
        payoutReversalAmount = payoutReversalAmount.add(amount);
        txnCount++;
    }

    public void addAdjust(BigDecimal signedAmount) {
        adjustAmount = adjustAmount.add(signedAmount);
        txnCount++;
    }
}
