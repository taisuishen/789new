package com.bingo789.risk.service;

import com.bingo789.common.mq.event.WithdrawRequestedEvent;
import com.bingo789.payment.api.PaymentClient;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import com.bingo789.risk.config.RiskProperties;
import com.bingo789.risk.domain.AmlAlert;
import com.bingo789.risk.domain.AmlAlertType;
import com.bingo789.risk.domain.RiskDecision;
import com.bingo789.risk.domain.RiskReviewTask;
import com.bingo789.risk.domain.Verdict;
import com.bingo789.risk.mapper.AmlAlertMapper;
import com.bingo789.risk.mapper.RiskDecisionMapper;
import com.bingo789.risk.mapper.RiskReviewTaskMapper;
import com.bingo789.risk.rule.LargeWithdrawalRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Every row risk writes for a withdrawal stores the line carried by the withdraw-requested event. */
class WithdrawAuditServiceTest {

    static final RiskProperties PROPERTIES = new RiskProperties(
            new RiskProperties.Aml(new BigDecimal("500000"), null),
            new RiskProperties.Turnover(false), new RiskProperties.Velocity(3));

    private RiskDecisionMapper decisionMapper;
    private RiskReviewTaskMapper taskMapper;
    private AmlAlertMapper alertMapper;
    private PaymentClient paymentClient;
    private WithdrawAuditService service;

    @BeforeEach
    void setUp() {
        decisionMapper = mock(RiskDecisionMapper.class);
        taskMapper = mock(RiskReviewTaskMapper.class);
        alertMapper = mock(AmlAlertMapper.class);
        paymentClient = mock(PaymentClient.class);
        // the id MyBatis-Plus assigns on insert; deliver() marks the decision delivered by id
        when(decisionMapper.insert(any(RiskDecision.class))).thenAnswer(inv -> {
            inv.<RiskDecision>getArgument(0).setId(1L);
            return 1;
        });

        AmlAlertService amlAlertService = new AmlAlertService(alertMapper, PROPERTIES);
        service = new WithdrawAuditService(List.of(new LargeWithdrawalRule(PROPERTIES, amlAlertService)),
                decisionMapper, taskMapper, paymentClient, inlineTransactions());
    }

    @Test
    void reviewedWithdrawalStoresTheLineOnTheDecisionTheTaskAndTheAlert() {
        service.onWithdrawRequested(requested(2, new BigDecimal("600000")));

        RiskDecision decision = insertedDecision();
        assertThat(decision.getVerdict()).isEqualTo(Verdict.REVIEW);
        assertThat(decision.getUserLine()).isEqualTo(2);

        ArgumentCaptor<RiskReviewTask> task = ArgumentCaptor.forClass(RiskReviewTask.class);
        verify(taskMapper).insert(task.capture());
        assertThat(task.getValue().getUserLine()).isEqualTo(2);

        ArgumentCaptor<AmlAlert> alert = ArgumentCaptor.forClass(AmlAlert.class);
        verify(alertMapper).insert(alert.capture());
        assertThat(alert.getValue().getAlertType()).isEqualTo(AmlAlertType.LARGE_WITHDRAWAL);
        assertThat(alert.getValue().getUserLine()).isEqualTo(2);

        verifyNoInteractions(paymentClient);
    }

    @Test
    void passedWithdrawalStoresTheLineAndDeliversTheUsualCommand() {
        service.onWithdrawRequested(requested(2, new BigDecimal("100")));

        RiskDecision decision = insertedDecision();
        assertThat(decision.getFinalDecision()).isEqualTo(WithdrawAuditCommand.Decision.APPROVED);
        assertThat(decision.getUserLine()).isEqualTo(2);
        verify(taskMapper, never()).insert(any(RiskReviewTask.class));
        verifyNoInteractions(alertMapper);
        verify(paymentClient).auditWithdrawal(new WithdrawAuditCommand("W42", WithdrawAuditCommand.Decision.APPROVED,
                "all risk rules passed", WithdrawAuditService.SYSTEM_AUDITOR));
        verify(decisionMapper).markDelivered(1L);
    }

    private RiskDecision insertedDecision() {
        ArgumentCaptor<RiskDecision> decision = ArgumentCaptor.forClass(RiskDecision.class);
        verify(decisionMapper).insert(decision.capture());
        return decision.getValue();
    }

    private static WithdrawRequestedEvent requested(int line, BigDecimal amount) {
        return new WithdrawRequestedEvent("W42", 7L, line, "PHP", amount, "GCASH", "10.0.0.1", "device-1", Instant.now());
    }

    /** Runs TransactionTemplate callbacks inline; the mappers are mocks. */
    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }
}
