package com.bingo789.payment.service;

import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.common.mq.outbox.OutboxService;
import com.bingo789.payment.channel.ChannelOutcome;
import com.bingo789.payment.channel.ChannelRegistry;
import com.bingo789.payment.channel.DepositInitiation;
import com.bingo789.payment.channel.DepositResult;
import com.bingo789.payment.channel.PaymentChannel;
import com.bingo789.payment.common.UserActionLock;
import com.bingo789.payment.domain.ChannelConfig;
import com.bingo789.payment.domain.DepositOrder;
import com.bingo789.payment.domain.DepositStatus;
import com.bingo789.payment.mapper.DepositOrderMapper;
import com.bingo789.payment.mapper.PlayerFirstDepositMapper;
import com.bingo789.payment.web.dto.CreateDepositRequest;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The deposit order snapshots the player's line; its events carry the stored value, never a fresh lookup. */
class DepositServiceTest {

    private static final long USER = 7L;
    private static final String CHANNEL = "GCASH";

    private DepositOrderMapper depositMapper;
    private UserClient userClient;
    private OutboxService outboxService;
    private PaymentChannel channel;
    private DepositService service;

    @BeforeEach
    void setUp() {
        depositMapper = mock(DepositOrderMapper.class);
        userClient = mock(UserClient.class);
        outboxService = mock(OutboxService.class);
        channel = mock(PaymentChannel.class);
        PlayerFirstDepositMapper firstDepositMapper = mock(PlayerFirstDepositMapper.class);
        ChannelConfigService channelConfigService = mock(ChannelConfigService.class);
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        UserActionLock userActionLock = mock(UserActionLock.class);
        WalletClient walletClient = mock(WalletClient.class);

        ChannelConfig config = new ChannelConfig();
        config.setCode(CHANNEL);
        when(channelConfigService.requireUsable(any(), any(), any(), any())).thenReturn(config);
        when(channelRegistry.require(CHANNEL)).thenReturn(channel);
        when(channelRegistry.find(CHANNEL)).thenReturn(Optional.of(channel));
        when(channel.createDeposit(any())).thenReturn(new DepositInitiation("CH-1", "https://pay.example/1", null));
        when(userActionLock.withLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> inv.<Supplier<?>>getArgument(3).get());
        when(walletClient.platformTxn(any())).thenReturn(WalletResult.success(1L, "PHP", BigDecimal.TEN, BigDecimal.TEN));
        when(depositMapper.markSucceeded(anyLong(), any())).thenReturn(1);
        when(firstDepositMapper.insertIgnore(anyLong(), anyString())).thenReturn(1);

        service = new DepositService(depositMapper, firstDepositMapper, channelConfigService, channelRegistry,
                mock(DepositLimitChecker.class), userActionLock, userClient, walletClient, outboxService,
                InlineTransactions.template(), new SnowflakeIdGenerator(1));
    }

    @Test
    void orderStoresThePlayersLineAndTheSucceededEventCarriesIt() {
        when(userClient.playerStatus(USER)).thenReturn(player(2));

        service.create(USER, new CreateDepositRequest(new BigDecimal("100"), null, CHANNEL), "10.0.0.1", "device-1");

        DepositOrder order = insertedOrder();
        assertThat(order.getUserLine()).isEqualTo(2);

        // the player moves to another line before the channel confirms: the event keeps the order's line
        when(userClient.playerStatus(USER)).thenReturn(player(3));
        when(depositMapper.selectByOrderNo(order.getOrderNo())).thenReturn(order);
        assertThat(service.onNotify(CHANNEL, paid(order))).isTrue();

        DepositSucceededEvent event = succeededEvent();
        assertThat(event.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(event.userLine()).isEqualTo(2);
    }

    @Test
    void recoveryBuildsTheEventFromTheStoredOrder() {
        DepositOrder stored = storedOrder(2);
        when(channel.queryDeposit(stored)).thenReturn(paid(stored));

        service.recover(stored, BingoTime.now().minusHours(2));

        assertThat(succeededEvent().userLine()).isEqualTo(2);
        verifyNoInteractions(userClient);
    }

    @Test
    void lineNotSentByAnOlderUserServiceIsTheDefaultLine() {
        when(userClient.playerStatus(USER)).thenReturn(player(0));

        service.create(USER, new CreateDepositRequest(new BigDecimal("100"), null, CHANNEL), "10.0.0.1", "device-1");

        assertThat(insertedOrder().getUserLine()).isEqualTo(1);
    }

    private DepositOrder insertedOrder() {
        ArgumentCaptor<DepositOrder> inserted = ArgumentCaptor.forClass(DepositOrder.class);
        verify(depositMapper).insert(inserted.capture());
        return inserted.getValue();
    }

    private DepositSucceededEvent succeededEvent() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).save(eq(Topics.DEPOSIT_SUCCEEDED), anyString(), payload.capture());
        return (DepositSucceededEvent) payload.getValue();
    }

    private static PlayerStatusView player(int line) {
        return new PlayerStatusView(USER, line, "PHP", AccountStatus.ACTIVE, KycStatus.VERIFIED,
                true, true, true, null, null);
    }

    private static DepositResult paid(DepositOrder order) {
        return new DepositResult(order.getOrderNo(), "CH-1", order.getAmount(), order.getCurrency(), ChannelOutcome.SUCCEEDED, null);
    }

    /** An order as read back from deposit_order by the notify handler or the recovery job. */
    private static DepositOrder storedOrder(int line) {
        DepositOrder order = new DepositOrder();
        order.setId(42L);
        order.setOrderNo("D42");
        order.setUserId(USER);
        order.setUserLine(line);
        order.setCurrency("PHP");
        order.setAmount(new BigDecimal("100.0000"));
        order.setChannelCode(CHANNEL);
        order.setStatus(DepositStatus.PENDING);
        order.setFirstDeposit(false);
        order.setCreatedAt(BingoTime.now().minusMinutes(10));
        order.setUpdatedAt(BingoTime.now().minusMinutes(10));
        return order;
    }
}
