package com.bingo789.reconcile.model;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** ggr_daily aggregated over all user lines by (stat_date, provider, currency) or, for the RTP monitor, by game. */
@Getter
@Setter
public class GgrRow {

    private LocalDate statDate;
    private String providerCode;
    private String gameCode;
    private String currency;
    private BigDecimal bet;
    private BigDecimal payout;
    private BigDecimal ggr;
    private Long betCount;
}
