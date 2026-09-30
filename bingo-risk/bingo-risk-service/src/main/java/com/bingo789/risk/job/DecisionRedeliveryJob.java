package com.bingo789.risk.job;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.risk.domain.RiskDecision;
import com.bingo789.risk.mapper.RiskDecisionMapper;
import com.bingo789.risk.service.WithdrawAuditService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Suggested schedule: every minute. Safety net for final decisions payment has not acknowledged (manual decisions
 * whose delivery failed, or automatic ones whose message went to the DLQ).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionRedeliveryJob {

    private static final Duration GRACE = Duration.ofMinutes(1);
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PER_RUN = 2_000;

    private final RiskDecisionMapper decisionMapper;
    private final WithdrawAuditService auditService;

    @XxlJob("riskDecisionRedeliveryJob")
    public void redeliver() {
        LocalDateTime before = BingoTime.now().minus(GRACE);
        long lastId = 0;
        int scanned = 0;
        int failed = 0;
        while (scanned < MAX_PER_RUN) {
            List<RiskDecision> page = decisionMapper.selectUndelivered(before, lastId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (RiskDecision decision : page) {
                lastId = decision.getId();
                scanned++;
                try {
                    auditService.deliver(decision);
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("redelivery of the decision on {} failed", decision.getOrderNo(), e);
                }
            }
        }
        XxlJobHelper.log("risk decision redelivery: scanned={}, failed={}", scanned, failed);
    }
}
