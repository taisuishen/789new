package com.bingo789.risk.service;

import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.risk.domain.AmlAlert;
import com.bingo789.risk.domain.AmlAlertStatus;
import com.bingo789.risk.domain.AmlAlertType;
import com.bingo789.risk.mapper.AmlAlertMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AmlAlertServiceTest {

    private final AmlAlertMapper alertMapper = mock(AmlAlertMapper.class);
    private final AmlAlertService service = new AmlAlertService(alertMapper, WithdrawAuditServiceTest.PROPERTIES);

    @Test
    void largeDepositAlertStoresTheEventsLine() {
        service.checkLargeDeposit(deposit(2, new BigDecimal("600000")));

        ArgumentCaptor<AmlAlert> alert = ArgumentCaptor.forClass(AmlAlert.class);
        verify(alertMapper).insert(alert.capture());
        assertThat(alert.getValue().getAlertType()).isEqualTo(AmlAlertType.LARGE_DEPOSIT);
        assertThat(alert.getValue().getStatus()).isEqualTo(AmlAlertStatus.OPEN);
        assertThat(alert.getValue().getRefNo()).isEqualTo("D42");
        assertThat(alert.getValue().getUserLine()).isEqualTo(2);
    }

    private static DepositSucceededEvent deposit(int line, BigDecimal amount) {
        return new DepositSucceededEvent("D42", 7L, line, "PHP", amount, "GCASH", true, Instant.now());
    }
}
