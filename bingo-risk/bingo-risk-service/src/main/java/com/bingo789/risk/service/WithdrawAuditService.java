package com.bingo789.risk.service;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.WithdrawRequestedEvent;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.payment.api.PaymentClient;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import com.bingo789.risk.common.Texts;
import com.bingo789.risk.domain.ReviewStatus;
import com.bingo789.risk.domain.RiskDecision;
import com.bingo789.risk.domain.RiskReviewTask;
import com.bingo789.risk.domain.Verdict;
import com.bingo789.risk.mapper.RiskDecisionMapper;
import com.bingo789.risk.mapper.RiskReviewTaskMapper;
import com.bingo789.risk.rule.RiskRule;
import com.bingo789.risk.rule.RuleHit;
import com.bingo789.risk.rule.RuleOutcome;
import com.bingo789.risk.rule.WithdrawContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Withdrawal audit: evaluate every rule, store the decision (order_no unique), deliver PASS / REJECT to payment,
 * open a review task for REVIEW.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawAuditService {

    public static final String SYSTEM_AUDITOR = "SYSTEM";

    /** Ordered by {@code @Order}. */
    private final List<RiskRule> rules;
    private final RiskDecisionMapper decisionMapper;
    private final RiskReviewTaskMapper taskMapper;
    private final PaymentClient paymentClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * Consumer entry point, idempotent per orderNo: a redelivered message finds the stored decision and only
     * re-sends it if payment has not acknowledged it yet. Delivery failures propagate so the message is redelivered.
     */
    public void onWithdrawRequested(WithdrawRequestedEvent event) {
        RiskDecision decision = MasterRoute.run(() -> decisionMapper.selectByOrderNo(event.orderNo()));
        if (decision == null) {
            decision = evaluateAndStore(event);
        }
        deliver(decision);
    }

    /**
     * Sends the final decision to payment unless it was acknowledged already. Payment is idempotent per order
     * (same decision = no-op), so re-sending after a lost response is safe.
     */
    public void deliver(RiskDecision decision) {
        if (decision.getFinalDecision() == null || Boolean.TRUE.equals(decision.getDelivered())) {
            return;
        }
        paymentClient.auditWithdrawal(new WithdrawAuditCommand(decision.getOrderNo(), decision.getFinalDecision(),
                Texts.truncate(decision.getFinalReason(), 512), decision.getAuditor()));
        decisionMapper.markDelivered(decision.getId());
        decision.setDelivered(true);
        log.info("withdrawal {} {} by {} delivered to payment", decision.getOrderNo(), decision.getFinalDecision(), decision.getAuditor());
    }

    private RiskDecision evaluateAndStore(WithdrawRequestedEvent event) {
        WithdrawContext ctx = WithdrawContext.of(event);
        List<RuleHit> hits = new ArrayList<>();
        for (RiskRule rule : rules) {
            RuleOutcome outcome = rule.evaluate(ctx);
            if (outcome.verdict() != Verdict.PASS) {
                hits.add(new RuleHit(rule.code(), outcome.verdict(), outcome.reason()));
            }
        }
        Verdict verdict = hits.stream().map(RuleHit::verdict).max(Comparator.naturalOrder()).orElse(Verdict.PASS);
        String reasons = hits.stream().map(h -> h.rule() + ": " + h.reason()).collect(Collectors.joining("; "));

        LocalDateTime now = BingoTime.now();
        RiskDecision decision = new RiskDecision();
        decision.setOrderNo(ctx.orderNo());
        decision.setUserId(ctx.userId());
        decision.setUserLine(ctx.userLine());
        decision.setCurrency(ctx.currency());
        decision.setAmount(ctx.amount());
        decision.setVerdict(verdict);
        decision.setRuleHits(JsonUtils.toJson(hits));
        decision.setDelivered(false);
        decision.setCreatedAt(now);
        decision.setUpdatedAt(now);
        if (verdict == Verdict.PASS) {
            decision.setFinalDecision(WithdrawAuditCommand.Decision.APPROVED);
            decision.setFinalReason("all risk rules passed");
            decision.setAuditor(SYSTEM_AUDITOR);
        } else if (verdict == Verdict.REJECT) {
            decision.setFinalDecision(WithdrawAuditCommand.Decision.REJECTED);
            decision.setFinalReason(Texts.truncate(reasons, 512));
            decision.setAuditor(SYSTEM_AUDITOR);
        }

        try {
            transactionTemplate.executeWithoutResult(tx -> {
                decisionMapper.insert(decision);
                if (verdict == Verdict.REVIEW) {
                    taskMapper.insert(newTask(ctx, reasons, now));
                }
            });
        } catch (RuntimeException e) {
            if (!DuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
            // a concurrent delivery of the same message stored its decision first
            return MasterRoute.run(() -> decisionMapper.selectByOrderNo(ctx.orderNo()));
        }
        log.info("withdrawal {} evaluated: {} {}", ctx.orderNo(), verdict, reasons);
        return decision;
    }

    private static RiskReviewTask newTask(WithdrawContext ctx, String reasons, LocalDateTime now) {
        RiskReviewTask task = new RiskReviewTask();
        task.setOrderNo(ctx.orderNo());
        task.setUserId(ctx.userId());
        task.setUserLine(ctx.userLine());
        task.setCurrency(ctx.currency());
        task.setAmount(ctx.amount());
        task.setReasons(Texts.truncate(reasons, 1024));
        task.setStatus(ReviewStatus.PENDING);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }
}
