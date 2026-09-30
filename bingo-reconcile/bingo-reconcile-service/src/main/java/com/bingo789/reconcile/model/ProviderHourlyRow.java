package com.bingo789.reconcile.model;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** In-batch delta for one recon_provider_hourly row. */
@Getter
public class ProviderHourlyRow {

    private final LocalDateTime statHour;
    private final int userLine;
    private final String providerCode;
    private final String gameCode;
    private final String currency;
    private BigDecimal betAmount = BigDecimal.ZERO;
    private BigDecimal payoutAmount = BigDecimal.ZERO;
    private long recordCount;

    public ProviderHourlyRow(HourKey key) {
        this.statHour = key.statHour();
        this.userLine = key.userLine();
        this.providerCode = key.providerCode();
        this.gameCode = key.gameCode();
        this.currency = key.currency();
    }

    public void add(BigDecimal bet, BigDecimal payout) {
        betAmount = betAmount.add(bet);
        payoutAmount = payoutAmount.add(payout);
        recordCount++;
    }
}
