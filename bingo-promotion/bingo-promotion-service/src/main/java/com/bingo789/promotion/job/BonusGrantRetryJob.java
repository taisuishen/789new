package com.bingo789.promotion.job;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.domain.BonusGrant;
import com.bingo789.promotion.mapper.BonusGrantMapper;
import com.bingo789.promotion.service.BonusGrantService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Suggested schedule: every 5 minutes. Safety net for grants left PENDING (message redelivery exhausted / DLQ).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BonusGrantRetryJob {

    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final int PAGE_SIZE = 200;

    private final BonusGrantMapper grantMapper;
    private final BonusGrantService bonusGrantService;

    @XxlJob("bonusGrantRetryJob")
    public void retry() {
        LocalDateTime before = BingoTime.now().minus(GRACE);
        long afterId = 0;
        int attempted = 0;
        int failures = 0;
        while (true) {
            List<BonusGrant> page = grantMapper.selectPendingBefore(before, afterId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (BonusGrant grant : page) {
                afterId = grant.getId();
                attempted++;
                try {
                    bonusGrantService.pay(grant);
                } catch (RuntimeException e) {
                    failures++;
                    log.warn("bonus grant retry failed for {}", grant.getBizNo(), e);
                }
            }
        }
        XxlJobHelper.log("bonus grant retry: attempted={}, failures={}", attempted, failures);
    }
}
