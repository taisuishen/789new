package com.bingo789.game.transfer;

import com.bingo789.common.core.time.BingoTime;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Finishes transfer orders left in a non-terminal state (timeouts, crashes). Schedule every minute. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferRecoveryJob {

    private static final int BATCH = 200;
    private static final int ALERT_AFTER_ATTEMPTS = 20;

    private final TransferOrderMapper mapper;
    private final TransferWalletService transferService;

    @XxlJob("transferRecoveryJob")
    public void run() {
        List<TransferOrder> pending = mapper.findPending(LocalDateTime.now(BingoTime.ZONE).minusMinutes(1), BATCH);
        int failures = 0;
        for (TransferOrder order : pending) {
            if (order.getAttempts() != null && order.getAttempts() >= ALERT_AFTER_ATTEMPTS) {
                log.error("transfer order {} still {} after {} attempts, needs manual reconciliation with provider {}",
                        order.getOrderNo(), order.getStatus(), order.getAttempts(), order.getProviderCode());
            }
            try {
                transferService.recover(order);
            } catch (Exception e) {
                failures++;
                log.warn("transfer recovery failed for {}", order.getOrderNo(), e);
            }
        }
        XxlJobHelper.log("transfer recovery: {} pending, {} failed", pending.size(), failures);
    }
}
