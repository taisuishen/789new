package com.bingo789.promotion.service;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.BonusGrantedEvent;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.common.mq.outbox.OutboxService;
import com.bingo789.promotion.PromotionFixtures;
import com.bingo789.promotion.domain.BonusGrant;
import com.bingo789.promotion.domain.BonusStatus;
import com.bingo789.promotion.mapper.BonusGrantMapper;
import com.bingo789.promotion.mapper.PromotionMapper;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static com.bingo789.promotion.PromotionFixtures.online;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Which FIRST_DEPOSIT promotion applies to a deposit, and what the grant and its event carry. */
class BonusGrantServiceTest {

    private static final long USER = 7L;
    private static final String FD = "FIRST_DEPOSIT";
    private static final Instant DEPOSITED_AT = LocalDateTime.of(2026, 10, 5, 12, 0).toInstant(BingoTime.ZONE);
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final String LINE_1_TERMS = """
            {"percent":50,"maxAmount":100,"turnover":{"multiplier":5}}""";
    private static final String LINE_2_TERMS = """
            {"percent":100,"maxAmount":1000,"turnover":{"multiplier":10,"scope":"GAME_TYPE","scopeValue":"SLOT"}}""";

    private BonusGrantMapper grantMapper;
    private PromotionMapper promotionMapper;
    private UserClient userClient;
    private WalletClient walletClient;
    private OutboxService outboxService;
    private BonusGrantService service;

    @BeforeEach
    void setUp() {
        grantMapper = mock(BonusGrantMapper.class);
        promotionMapper = mock(PromotionMapper.class);
        userClient = mock(UserClient.class);
        walletClient = mock(WalletClient.class);
        outboxService = mock(OutboxService.class);
        when(grantMapper.insert(any(BonusGrant.class))).thenAnswer(inv -> {
            inv.<BonusGrant>getArgument(0).setId(100L);
            return 1;
        });
        when(grantMapper.markPaid(anyLong())).thenReturn(1);
        when(walletClient.platformTxn(any())).thenReturn(WalletResult.success(1L, "PHP", BigDecimal.TEN, BigDecimal.TEN));
        service = service(true);
    }

    @Test
    void aLine2PlayerGetsTheLine2Promotion() {
        when(userClient.playerStatus(USER)).thenReturn(PromotionFixtures.player(USER, 2));
        // as the mapper orders them: sort desc, id desc
        when(promotionMapper.selectOnlineActiveAt(eq(FD), eq(BingoTime.toLocal(DEPOSITED_AT)))).thenReturn(List.of(
                online(11, FD, "[1]", FROM, TO, 9, LINE_1_TERMS),
                online(12, FD, "[2,3]", FROM, TO, 0, LINE_2_TERMS)));

        service.onFirstDeposit(deposit(2, "300"));

        BonusGrant grant = insertedGrant();
        assertThat(grant.getPromotionId()).isEqualTo(12L);
        assertThat(grant.getUserLine()).isEqualTo(2);
        assertThat(grant.getAmount()).isEqualByComparingTo("300");
        assertThat(grant.getTurnoverMultiplier()).isEqualByComparingTo("10");
        assertThat(grant.getTurnoverScope()).isEqualTo("GAME_TYPE");
        assertThat(grant.getTurnoverScopeValue()).isEqualTo("SLOT");
        BonusGrantedEvent event = grantedEvent();
        assertThat(event.userLine()).isEqualTo(2);
        assertThat(event.amount()).isEqualByComparingTo("300");
        assertThat(event.bonusType()).isEqualTo(FD);
        assertThat(event.turnoverMultiplier()).isEqualByComparingTo("10");
        assertThat(event.turnoverScope()).isEqualTo("GAME_TYPE");
        assertThat(event.turnoverScopeValue()).isEqualTo("SLOT");
    }

    @Test
    void aLine1OnlyPromotionGivesALine2PlayerNoBonus() {
        when(promotionMapper.selectOnlineActiveAt(eq(FD), any())).thenReturn(List.of(
                online(11, FD, "[1]", FROM, TO, 0, LINE_1_TERMS)));

        service.onFirstDeposit(deposit(2, "300"));

        verify(grantMapper, never()).insert(any(BonusGrant.class));
        verifyNoInteractions(walletClient, outboxService, userClient);
    }

    @Test
    void aPromotionForOtherCurrenciesIsSkipped() {
        when(userClient.playerStatus(USER)).thenReturn(PromotionFixtures.player(USER, 1));
        when(promotionMapper.selectOnlineActiveAt(eq(FD), any())).thenReturn(List.of(
                online(21, FD, "[1]", FROM, TO, 5, """
                        {"percent":100,"maxAmount":50,"currencies":["USD"],"turnover":{"multiplier":1}}"""),
                online(22, FD, "[1]", FROM, TO, 0, LINE_1_TERMS)));

        service.onFirstDeposit(deposit(1, "1000"));

        BonusGrant grant = insertedGrant();
        assertThat(grant.getPromotionId()).isEqualTo(22L);
        // 50% of 1000, capped at 100
        assertThat(grant.getAmount()).isEqualByComparingTo("100");
        assertThat(grant.getTurnoverScope()).isEqualTo("ALL");
        assertThat(grant.getTurnoverScopeValue()).isNull();
    }

    @Test
    void theBonusIsRoundedDown() {
        when(userClient.playerStatus(USER)).thenReturn(PromotionFixtures.player(USER, 1));
        when(promotionMapper.selectOnlineActiveAt(eq(FD), any())).thenReturn(List.of(online(31, FD, "[1]", FROM, TO, 0, """
                {"percent":33.3333,"maxAmount":1000,"turnover":{"multiplier":1}}""")));

        service.onFirstDeposit(deposit(1, "10"));

        assertThat(insertedGrant().getAmount()).isEqualByComparingTo("3.3333");
    }

    @Test
    void theKillSwitchStopsEveryFirstDepositPromotion() {
        service(false).onFirstDeposit(deposit(1, "300"));

        verifyNoInteractions(promotionMapper, grantMapper, userClient, walletClient, outboxService);
    }

    @Test
    void aPlayerWhoMayNotPlayGetsNoBonus() {
        when(userClient.playerStatus(USER)).thenReturn(new PlayerStatusView(USER, 1, "PHP", AccountStatus.ACTIVE,
                KycStatus.VERIFIED, false, false, true, null, "SELF_EXCLUDED"));
        when(promotionMapper.selectOnlineActiveAt(eq(FD), any())).thenReturn(List.of(online(11, FD, "[1]", FROM, TO, 0, LINE_1_TERMS)));

        service.onFirstDeposit(deposit(1, "300"));

        verify(grantMapper, never()).insert(any(BonusGrant.class));
    }

    @Test
    void invalidStoredTermsFailTheMessageInsteadOfSkippingThePromotion() {
        when(promotionMapper.selectOnlineActiveAt(eq(FD), any())).thenReturn(List.of(online(11, FD, "[1]", FROM, TO, 0, """
                {"percent":100,"turnover":{"multiplier":1}}""")));

        assertThatThrownBy(() -> service.onFirstDeposit(deposit(1, "300")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion 11");
        verify(grantMapper, never()).insert(any(BonusGrant.class));
    }

    @Test
    void theRetryJobPublishesTheLineAndTermsStoredWithTheGrant() {
        BonusGrant stored = new BonusGrant();
        stored.setId(100L);
        stored.setBizNo("FDB-D9");
        stored.setUserId(USER);
        stored.setUserLine(3);
        stored.setCurrency("PHP");
        stored.setAmount(new BigDecimal("50.0000"));
        stored.setBonusType(FD);
        stored.setPromotionId(12L);
        stored.setStatus(BonusStatus.PENDING);
        stored.setTurnoverMultiplier(new BigDecimal("10.0000"));
        stored.setTurnoverScope("GAME");
        stored.setTurnoverScopeValue("PG:fortune-tiger");

        assertThat(service.pay(stored)).isEqualTo(PayOutcome.PAID);

        BonusGrantedEvent event = grantedEvent();
        assertThat(event.userLine()).isEqualTo(3);
        assertThat(event.turnoverScope()).isEqualTo("GAME");
        assertThat(event.turnoverScopeValue()).isEqualTo("PG:fortune-tiger");
        verifyNoInteractions(userClient, promotionMapper);
    }

    private BonusGrantService service(boolean enabled) {
        return new BonusGrantService(grantMapper, promotionMapper, userClient, walletClient, outboxService,
                InlineTransactions.template(), PromotionFixtures.properties(enabled));
    }

    private static DepositSucceededEvent deposit(int line, String amount) {
        return new DepositSucceededEvent("D1", USER, line, "PHP", new BigDecimal(amount), "GCASH", true, DEPOSITED_AT);
    }

    private BonusGrant insertedGrant() {
        ArgumentCaptor<BonusGrant> inserted = ArgumentCaptor.forClass(BonusGrant.class);
        verify(grantMapper).insert(inserted.capture());
        return inserted.getValue();
    }

    private BonusGrantedEvent grantedEvent() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).save(eq(Topics.BONUS_GRANTED), anyString(), payload.capture());
        return (BonusGrantedEvent) payload.getValue();
    }
}
