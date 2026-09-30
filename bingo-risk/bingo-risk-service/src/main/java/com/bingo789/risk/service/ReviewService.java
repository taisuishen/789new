package com.bingo789.risk.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bingo789.common.core.BizException;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import com.bingo789.risk.common.PageResult;
import com.bingo789.risk.common.RiskErrorCode;
import com.bingo789.risk.domain.ReviewStatus;
import com.bingo789.risk.domain.RiskDecision;
import com.bingo789.risk.domain.RiskReviewTask;
import com.bingo789.risk.mapper.RiskDecisionMapper;
import com.bingo789.risk.mapper.RiskReviewTaskMapper;
import com.bingo789.risk.web.dto.ReviewDecisionView;
import com.bingo789.risk.web.dto.ReviewTaskView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Manual review of withdrawals the rules sent to REVIEW. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final RiskReviewTaskMapper taskMapper;
    private final RiskDecisionMapper decisionMapper;
    private final WithdrawAuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public PageResult<ReviewTaskView> list(ReviewStatus status, long page, long size) {
        Page<RiskReviewTask> request = PageResult.request(page, size);
        Page<RiskReviewTask> result = taskMapper.selectPage(request, Wrappers.<RiskReviewTask>lambdaQuery()
                .eq(status != null, RiskReviewTask::getStatus, status)
                .orderByAsc(RiskReviewTask::getCreatedAt));
        return PageResult.of(result, ReviewTaskView::of);
    }

    /**
     * Records the operator's decision (task + final decision in one transaction), then delivers it to payment.
     * Repeating the same decision only re-delivers; a different decision on a decided task is refused.
     * A failed delivery is retried by DecisionRedeliveryJob.
     */
    public ReviewDecisionView decide(long taskId, WithdrawAuditCommand.Decision decision, String reason, String operator) {
        RiskReviewTask task = MasterRoute.run(() -> taskMapper.selectById(taskId));
        if (task == null) {
            throw new BizException(RiskErrorCode.REVIEW_NOT_FOUND);
        }
        ReviewStatus target = decision == WithdrawAuditCommand.Decision.APPROVED ? ReviewStatus.APPROVED : ReviewStatus.REJECTED;
        transactionTemplate.executeWithoutResult(tx -> {
            if (taskMapper.decide(taskId, target, operator, reason) == 0) {
                RiskReviewTask current = taskMapper.selectById(taskId);
                if (current.getStatus() != target) {
                    throw new BizException(RiskErrorCode.REVIEW_ALREADY_DECIDED, "review task is already " + current.getStatus());
                }
                return;
            }
            decisionMapper.setFinalDecision(task.getOrderNo(), decision, reason, operator);
        });
        log.info("review task {} of withdrawal {} decided {} by {}", taskId, task.getOrderNo(), decision, operator);

        RiskDecision stored = MasterRoute.run(() -> decisionMapper.selectByOrderNo(task.getOrderNo()));
        boolean delivered;
        try {
            auditService.deliver(stored);
            delivered = true;
        } catch (RuntimeException e) {
            log.warn("delivery of the decision on {} failed, DecisionRedeliveryJob will retry", task.getOrderNo(), e);
            delivered = false;
        }
        return new ReviewDecisionView(task.getOrderNo(), stored.getFinalDecision(), delivered);
    }
}
