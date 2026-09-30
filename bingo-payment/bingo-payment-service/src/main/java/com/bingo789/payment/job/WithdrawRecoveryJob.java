package com.bingo789.payment.job;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.payment.config.PaymentProperties;
import com.bingo789.payment.domain.WithdrawOrder;
import com.bingo789.payment.domain.WithdrawStatus;
import com.bingo789.payment.mapper.WithdrawOrderMapper;
import com.bingo789.payment.service.WithdrawService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * Suggested schedule: every minute, "first" routing, serial execution.
 * Resumes withdrawals whose last step had an unknown outcome:
 * <ol>
 *   <li>CREATED: retry WITHDRAW_FREEZE with the same bizNo</li>
 *   <li>PENDING_AUDIT with a recorded REJECTED decision: retry the unfreeze</li>
 *   <li>APPROVED: submit the payout</li>
 *   <li>PAYING: query the channel. Unknown stays PAYING; never unfreeze on an unknown outcome.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawRecoveryJob {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PER_STATUS = 2_000;

    private final WithdrawOrderMapper withdrawMapper;
    private final WithdrawService withdrawService;
    private final PaymentProperties properties;

    @XxlJob("withdrawRecoveryJob")
    public void recover() {
        LocalDateTime now = BingoTime.now();
        LocalDateTime idleBefore = now.minus(properties.withdraw().recoverAfter());
        LocalDateTime alertBefore = now.minus(properties.withdraw().payingAlertAfter());

        int created = scan(WithdrawStatus.CREATED, false, idleBefore, withdrawService::freezeAndRequestAudit);
        int decided = scan(WithdrawStatus.PENDING_AUDIT, true, idleBefore, withdrawService::continueAfterDecision);
        int approved = scan(WithdrawStatus.APPROVED, false, idleBefore, withdrawService::startPayout);
        int paying = scan(WithdrawStatus.PAYING, false, idleBefore, order -> withdrawService.recoverPayout(order, alertBefore));
        XxlJobHelper.log("withdraw recovery: created={}, decided={}, approved={}, paying={}", created, decided, approved, paying);
    }

    private int scan(WithdrawStatus status, boolean decidedOnly, LocalDateTime idleBefore, Consumer<WithdrawOrder> action) {
        long lastId = 0;
        int count = 0;
        while (count < MAX_PER_STATUS) {
            List<WithdrawOrder> page = withdrawMapper.selectStale(status, decidedOnly, idleBefore, lastId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (WithdrawOrder order : page) {
                lastId = order.getId();
                count++;
                try {
                    action.accept(order);
                } catch (RuntimeException e) {
                    log.warn("withdraw recovery failed for {} in {}, retried next run", order.getOrderNo(), status, e);
                }
            }
        }
        return count;
    }
}
