package com.bingo789.payment.service;

import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.WithdrawFinishedEvent;
import com.bingo789.common.mq.event.WithdrawRequestedEvent;
import com.bingo789.common.mq.outbox.OutboxService;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import com.bingo789.payment.channel.ChannelOutcome;
import com.bingo789.payment.channel.ChannelRegistry;
import com.bingo789.payment.channel.PaymentChannel;
import com.bingo789.payment.channel.PayoutResult;
import com.bingo789.payment.domain.ChannelConfig;
import com.bingo789.payment.domain.WithdrawOrder;
import com.bingo789.payment.domain.WithdrawStatus;
import com.bingo789.payment.mapper.WithdrawOrderMapper;
import com.bingo789.payment.web.dto.CreateWithdrawRequest;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.WalletResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The withdrawal order snapshots the player's line; its events carry the stored value, never a fresh lookup. */
class WithdrawServiceTest {

    private static final long USER = 7L;
    private static final String CHANNEL = "GCASH";

    private WithdrawOrderMapper withdrawMapper;
    private UserClient userClient;
    private OutboxService outboxService;
    private WithdrawService service;

    @BeforeEach
    void setUp() {
        withdrawMapper = mock(WithdrawOrderMapper.class);
        userClient = mock(UserClient.class);
        outboxService = mock(OutboxService.class);
        ChannelConfigService channelConfigService = mock(ChannelConfigService.class);
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        PaymentChannel channel = mock(PaymentChannel.class);
        WalletClient walletClient = mock(WalletClient.class);

        ChannelConfig config = new ChannelConfig();
        config.setCode(CHANNEL);
        when(channelConfigService.requireUsable(any(), any(), any(), any())).thenReturn(config);
        when(channelRegistry.find(CHANNEL)).thenReturn(Optional.of(channel));
        when(walletClient.platformTxn(any())).thenReturn(WalletResult.success(1L, "PHP", BigDecimal.TEN, BigDecimal.TEN));
        when(withdrawMapper.transition(anyLong(), any(), any())).thenReturn(1);
        when(withdrawMapper.markPaid(anyLong(), any())).thenReturn(1);

        service = new WithdrawService(withdrawMapper, channelConfigService, channelRegistry, userClient, walletClient,
                outboxService, InlineTransactions.template(), new SnowflakeIdGenerator(1));
    }

    @Test
    void orderStoresThePlayersLineAndTheRequestedEventCarriesIt() {
        when(userClient.playerStatus(USER)).thenReturn(player(2));

        service.create(USER, new CreateWithdrawRequest(new BigDecimal("500"), null, CHANNEL, "payee_token_01"),
                "10.0.0.1", "device-1");

        ArgumentCaptor<WithdrawOrder> inserted = ArgumentCaptor.forClass(WithdrawOrder.class);
        verify(withdrawMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getUserLine()).isEqualTo(2);
        WithdrawRequestedEvent event = published(Topics.WITHDRAW_REQUESTED, WithdrawRequestedEvent.class);
        assertThat(event.orderNo()).isEqualTo(inserted.getValue().getOrderNo());
        assertThat(event.userLine()).isEqualTo(2);
    }

    @Test
    void freezeRetriedByTheRecoveryJobUsesTheStoredLine() {
        service.freezeAndRequestAudit(storedOrder(2, WithdrawStatus.CREATED));

        assertThat(published(Topics.WITHDRAW_REQUESTED, WithdrawRequestedEvent.class).userLine()).isEqualTo(2);
        verifyNoInteractions(userClient);
    }

    @Test
    void rejectionEventCarriesTheStoredLine() {
        WithdrawOrder order = storedOrder(2, WithdrawStatus.PENDING_AUDIT);
        order.setAuditDecision(WithdrawAuditCommand.Decision.REJECTED);
        order.setAuditReason("manual review");

        service.continueAfterDecision(order);

        WithdrawFinishedEvent event = published(Topics.WITHDRAW_FINISHED, WithdrawFinishedEvent.class);
        assertThat(event.status()).isEqualTo(WithdrawStatus.REJECTED.name());
        assertThat(event.userLine()).isEqualTo(2);
        verifyNoInteractions(userClient);
    }

    @Test
    void paidEventCarriesTheStoredLine() {
        WithdrawOrder order = storedOrder(2, WithdrawStatus.PAYING);
        when(withdrawMapper.selectByOrderNo(order.getOrderNo())).thenReturn(order);

        assertThat(service.onPayoutNotify(CHANNEL,
                new PayoutResult(order.getOrderNo(), "CH-P1", ChannelOutcome.SUCCEEDED, null))).isTrue();

        WithdrawFinishedEvent event = published(Topics.WITHDRAW_FINISHED, WithdrawFinishedEvent.class);
        assertThat(event.status()).isEqualTo(WithdrawStatus.SUCCEEDED.name());
        assertThat(event.userLine()).isEqualTo(2);
        verifyNoInteractions(userClient);
    }

    private <T> T published(String topic, Class<T> type) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).save(eq(topic), anyString(), payload.capture());
        return type.cast(payload.getValue());
    }

    private static PlayerStatusView player(int line) {
        return new PlayerStatusView(USER, line, "PHP", AccountStatus.ACTIVE, KycStatus.VERIFIED,
                true, true, true, null, null);
    }

    /** An order as read back from withdraw_order by the notify handler, the audit call or the recovery job. */
    private static WithdrawOrder storedOrder(int line, WithdrawStatus status) {
        WithdrawOrder order = new WithdrawOrder();
        order.setId(42L);
        order.setOrderNo("W42");
        order.setUserId(USER);
        order.setUserLine(line);
        order.setCurrency("PHP");
        order.setAmount(new BigDecimal("500.0000"));
        order.setFee(BigDecimal.ZERO);
        order.setChannelCode(CHANNEL);
        order.setPayeeRef("payee_token_01");
        order.setStatus(status);
        order.setCreatedAt(BingoTime.now().minusMinutes(10));
        order.setUpdatedAt(BingoTime.now().minusMinutes(10));
        return order;
    }
}
