package com.bingo789.reconcile.web.dto;

import com.bingo789.reconcile.model.GgrRow;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One reporting day of one provider and currency; bet and payout are net amounts. */
public record GgrView(
        LocalDate statDate,
        String providerCode,
        String currency,
        BigDecimal bet,
        BigDecimal payout,
        BigDecimal ggr,
        long betCount) {

    public static GgrView of(GgrRow r) {
        return new GgrView(r.getStatDate(), r.getProviderCode(), r.getCurrency(), r.getBet(), r.getPayout(), r.getGgr(),
                r.getBetCount() == null ? 0 : r.getBetCount());
    }
}
