package com.bingo789.payment.job;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.payment.config.PaymentProperties;
import com.bingo789.payment.domain.DepositOrder;
import com.bingo789.payment.mapper.DepositOrderMapper;
import com.bingo789.payment.service.DepositService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Suggested schedule: every minute, "first" routing, serial execution.
 * Queries the channel for deposits that were never notified; credits, fails or expires them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepositRecoveryJob {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PER_RUN = 5_000;

    private final DepositOrderMapper depositMapper;
    private final DepositService depositService;
    private final PaymentProperties properties;

    @XxlJob("depositRecoveryJob")
    public void recover() {
        LocalDateTime now = BingoTime.now();
        LocalDateTime recoverBefore = now.minus(properties.deposit().recoverAfter());
        LocalDateTime expireBefore = now.minus(properties.deposit().expireAfter());
        long lastId = 0;
        int scanned = 0;
        int failures = 0;
        while (scanned < MAX_PER_RUN) {
            List<DepositOrder> page = depositMapper.selectUnsettled(recoverBefore, lastId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (DepositOrder order : page) {
                lastId = order.getId();
                scanned++;
                try {
                    depositService.recover(order, expireBefore);
                } catch (RuntimeException e) {
                    failures++;
                    log.warn("deposit recovery failed for {}, retried next run", order.getOrderNo(), e);
                }
            }
        }
        XxlJobHelper.log("deposit recovery: scanned={}, failures={}", scanned, failures);
    }
}
