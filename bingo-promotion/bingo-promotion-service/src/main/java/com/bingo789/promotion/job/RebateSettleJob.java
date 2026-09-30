package com.bingo789.promotion.job;

import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.promotion.service.RebateService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Daily rebate settlement. Param: optional business day yyyy-MM-dd (default: yesterday in the business zone).
 * Schedule it a few hours after midnight business time so late provider settlements are in. Safe to re-run.
 * The terms come from the REBATE promotions active that day; one with invalid stored terms fails the run before
 * anything is written (fix it, then re-run the day).
 */
@Component
@RequiredArgsConstructor
public class RebateSettleJob {

    private final RebateService rebateService;
    private final PromotionProperties properties;

    @XxlJob("rebateSettleJob")
    public void settle() {
        LocalDate today = LocalDate.now(properties.zone());
        String param = XxlJobHelper.getJobParam();
        LocalDate statDate;
        try {
            statDate = param == null || param.isBlank() ? today.minusDays(1) : LocalDate.parse(param.trim());
        } catch (DateTimeParseException e) {
            XxlJobHelper.handleFail("invalid param '" + param + "', expected yyyy-MM-dd");
            return;
        }
        if (!statDate.isBefore(today)) {
            XxlJobHelper.handleFail("business day " + statDate + " is not closed yet");
            return;
        }
        int created = rebateService.computeRebates(statDate);
        RebateService.PayStats stats = rebateService.payPending();
        XxlJobHelper.log("rebates {}: created={}, paid={}, failed={}, unknown={}",
                statDate, created, stats.paid(), stats.failed(), stats.unknown());
    }
}
