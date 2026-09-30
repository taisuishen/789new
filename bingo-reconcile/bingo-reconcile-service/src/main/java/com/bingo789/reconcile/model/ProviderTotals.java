package com.bingo789.reconcile.model;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Per provider and currency totals of one period (net amounts on the platform side). */
@Getter
@Setter
public class ProviderTotals {

    private String providerCode;
    private String currency;
    private BigDecimal betAmount;
    private BigDecimal payoutAmount;
}
