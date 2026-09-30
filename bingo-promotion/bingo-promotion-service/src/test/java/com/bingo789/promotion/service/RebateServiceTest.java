package com.bingo789.promotion.service;

import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.BonusGrantedEvent;
import com.bingo789.common.mq.outbox.OutboxService;
import com.bingo789.promotion.PromotionFixtures;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.RebateRecord;
import com.bingo789.promotion.domain.RebateStatus;
import com.bingo789.promotion.domain.ValidBetDaily;
import com.bingo789.promotion.mapper.PromotionMapper;
import com.bingo789.promotion.mapper.RebateRecordMapper;
import com.bingo789.promotion.mapper.ValidBetDailyMapper;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.WalletResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.bingo789.promotion.PromotionFixtures.online;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RebateServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2100, 1, 1, 0, 0);
    private static final String REBATE = "REBATE";
    /** The former rebate_rule set-up as terms: default '*' 0.5%, DEMO 0.8% capped at 100 a day, PG excluded. */
    private static final String CLASSIC_TERMS = """
            {"defaultRate":0.005,"providers":{"DEMO":{"rate":0.008,"dailyCap":100},"PG":{"rate":0}},
             "turnover":{"multiplier":1}}""";
    private static final String LINE_2_TERMS = """
            {"defaultRate":0.01,"turnover":{"multiplier":3,"scope":"GAME_TYPE","scopeValue":"SLOT"}}""";

    private ValidBetDailyMapper validBetMapper;
    private PromotionMapper promotionMapper;
    private RebateRecordMapper recordMapper;
    private WalletClient walletClient;
    private OutboxService outboxService;
    private RebateService service;
    private final List<RebateRecord> inserted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        validBetMapper = mock(ValidBetDailyMapper.class);
        promotionMapper = mock(PromotionMapper.class);
        recordMapper = mock(RebateRecordMapper.class);
        walletClient = mock(WalletClient.class);
        outboxService = mock(OutboxService.class);
        when(recordMapper.insertIgnoreBatch(anyList())).thenAnswer(inv -> {
            List<RebateRecord> rows = inv.getArgument(0);
            inserted.addAll(rows);
            return rows.size();
        });
        service = new RebateService(validBetMapper, promotionMapper, recordMapper, walletClient, outboxService,
                InlineTransactions.template(), new SnowflakeIdGenerator(1), PromotionFixtures.properties(false));
    }

    @Test
    void amountsAreUnchangedForTheClassicScenarios() {
        programmes(online(21, REBATE, "[1]", FROM, TO, 0, CLASSIC_TERMS));
        validBets(
                // DEMO 8 + PG excluded + JILI (default rate) 10
                row(1, 1, "PHP", "DEMO", "1000", 0),
                row(1, 1, "PHP", "PG", "5000", 1),
                row(1, 1, "PHP", "JILI", "2000", 2),
                // DEMO 160 capped at 100 + JILI 1.66665, rounded down
                row(2, 1, "PHP", "DEMO", "20000", 0),
                row(2, 1, "PHP", "JILI", "333.33", 1),
                // only an excluded provider: no row
                row(3, 1, "PHP", "PG", "1000", 0),
                // one row per currency
                row(4, 1, "PHP", "JILI", "100", 0),
                row(4, 1, "USD", "JILI", "100.0001", 1),
                // rounds down to zero: no row
                row(5, 1, "PHP", "JILI", "0.01", 0));

        assertThat(service.computeRebates(DAY)).isEqualTo(4);

        assertThat(record(1, "PHP").getAmount()).isEqualByComparingTo("18");
        assertThat(record(1, "PHP").getValidBet()).isEqualByComparingTo("8000");
        assertThat(record(2, "PHP").getAmount()).isEqualByComparingTo("101.6666");
        assertThat(record(2, "PHP").getValidBet()).isEqualByComparingTo("20333.33");
        assertThat(record(4, "PHP").getAmount()).isEqualByComparingTo("0.5");
        assertThat(record(4, "USD").getAmount()).isEqualByComparingTo("0.5");
        assertThat(inserted).extracting(RebateRecord::getUserId).doesNotContain(3L, 5L);
        assertThat(inserted).allSatisfy(r -> {
            assertThat(r.getAmount().scale()).isEqualTo(4);
            assertThat(r.getStatDate()).isEqualTo(DAY);
            assertThat(r.getUserLine()).isEqualTo(1);
            assertThat(r.getPromotionId()).isEqualTo(21L);
            assertThat(r.getTurnoverMultiplier()).isEqualByComparingTo("1");
            assertThat(r.getTurnoverScope()).isEqualTo("ALL");
            assertThat(r.getTurnoverScopeValue()).isNull();
            assertThat(r.getStatus()).isEqualTo(RebateStatus.PENDING);
        });
    }

    @Test
    void theDefaultCapAppliesPerProviderAndAProvidersOwnRateHasItsOwnCap() {
        programmes(online(21, REBATE, "[1]", FROM, TO, 0, """
                {"defaultRate":0.01,"defaultDailyCap":5,"providers":{"DEMO":{"rate":0.01}},"turnover":{"multiplier":1}}"""));
        validBets(
                row(1, 1, "PHP", "JILI", "2000", 0),
                row(1, 1, "PHP", "TADA", "2000", 1),
                row(1, 1, "PHP", "DEMO", "2000", 2));

        service.computeRebates(DAY);

        // 5 + 5 (default cap per provider) + 20 (DEMO: own rate, no cap)
        assertThat(record(1, "PHP").getAmount()).isEqualByComparingTo("30");
    }

    @Test
    void withoutADefaultRateOnlyListedProvidersEarn() {
        programmes(online(21, REBATE, "[1]", FROM, TO, 0, """
                {"providers":{"DEMO":{"rate":0.01}},"turnover":{"multiplier":1}}"""));
        validBets(
                row(1, 1, "PHP", "DEMO", "1000", 0),
                row(1, 1, "PHP", "JILI", "1000", 1));

        service.computeRebates(DAY);

        assertThat(record(1, "PHP").getAmount()).isEqualByComparingTo("10");
        assertThat(record(1, "PHP").getValidBet()).isEqualByComparingTo("2000");
    }

    @Test
    void eachUserDayUsesTheProgrammeOfItsLine() {
        // as the mapper orders them: sort desc, id desc
        programmes(
                online(22, REBATE, "[2]", FROM, TO, 0, LINE_2_TERMS),
                online(21, REBATE, "[1]", FROM, TO, 0, CLASSIC_TERMS));
        validBets(
                row(1, 1, "PHP", "JILI", "1000", 0),
                row(2, 2, "PHP", "JILI", "1000", 0),
                row(3, 3, "PHP", "JILI", "1000", 0));

        assertThat(service.computeRebates(DAY)).isEqualTo(2);

        RebateRecord line1 = record(1, "PHP");
        assertThat(line1.getAmount()).isEqualByComparingTo("5");
        assertThat(line1.getPromotionId()).isEqualTo(21L);
        assertThat(line1.getUserLine()).isEqualTo(1);
        RebateRecord line2 = record(2, "PHP");
        assertThat(line2.getAmount()).isEqualByComparingTo("10");
        assertThat(line2.getPromotionId()).isEqualTo(22L);
        assertThat(line2.getUserLine()).isEqualTo(2);
        assertThat(line2.getTurnoverMultiplier()).isEqualByComparingTo("3");
        assertThat(line2.getTurnoverScope()).isEqualTo("GAME_TYPE");
        assertThat(line2.getTurnoverScopeValue()).isEqualTo("SLOT");
        // no programme for line 3
        assertThat(inserted).extracting(RebateRecord::getUserId).doesNotContain(3L);
    }

    @Test
    void aPlayerMigratedDuringTheDayIsRebatedOnTheLineAtTheEndOfTheDay() {
        programmes(
                online(22, REBATE, "[2]", FROM, TO, 0, LINE_2_TERMS),
                online(21, REBATE, "[1]", FROM, TO, 0, CLASSIC_TERMS));
        validBets(
                // last updated before the migration (line 1), then after it (line 2)
                row(4, 1, "PHP", "DEMO", "1000", 0),
                row(4, 2, "PHP", "JILI", "1000", 300));

        service.computeRebates(DAY);

        RebateRecord record = record(4, "PHP");
        assertThat(record.getUserLine()).isEqualTo(2);
        assertThat(record.getPromotionId()).isEqualTo(22L);
        // both providers at the line-2 default rate
        assertThat(record.getAmount()).isEqualByComparingTo("20");
    }

    @Test
    void theFirstProgrammeInPriorityOrderWins() {
        programmes(
                online(31, REBATE, "[1,2]", FROM, TO, 5, """
                        {"defaultRate":0.02,"turnover":{"multiplier":1}}"""),
                online(32, REBATE, "[1]", FROM, TO, 0, CLASSIC_TERMS));
        validBets(row(1, 1, "PHP", "JILI", "1000", 0));

        service.computeRebates(DAY);

        assertThat(record(1, "PHP").getPromotionId()).isEqualTo(31L);
        assertThat(record(1, "PHP").getAmount()).isEqualByComparingTo("20");
    }

    @Test
    void withoutAnOnlineProgrammeNothingIsComputed() {
        assertThat(service.computeRebates(DAY)).isZero();

        verifyNoInteractions(validBetMapper, recordMapper);
    }

    @Test
    void invalidStoredTermsFailTheRunBeforeAnythingIsWritten() {
        programmes(online(41, REBATE, "[1]", FROM, TO, 0, """
                {"turnover":{"multiplier":1}}"""));

        assertThatThrownBy(() -> service.computeRebates(DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion 41");
        verifyNoInteractions(validBetMapper, recordMapper);
    }

    @Test
    void thePaidEventCarriesTheLineAndTermsStoredWithTheRecord() {
        RebateRecord record = new RebateRecord();
        record.setId(900L);
        record.setStatDate(DAY);
        record.setUserId(2L);
        record.setUserLine(2);
        record.setCurrency("PHP");
        record.setValidBet(new BigDecimal("1000.0000"));
        record.setAmount(new BigDecimal("10.0000"));
        record.setPromotionId(22L);
        record.setTurnoverMultiplier(new BigDecimal("3.0000"));
        record.setTurnoverScope("GAME_TYPE");
        record.setTurnoverScopeValue("SLOT");
        record.setStatus(RebateStatus.PENDING);
        when(recordMapper.selectPending(anyLong(), anyInt())).thenReturn(List.of(record), List.of());
        when(recordMapper.markPaid(900L)).thenReturn(1);
        when(walletClient.platformTxn(any())).thenReturn(WalletResult.success(1L, "PHP", BigDecimal.TEN, BigDecimal.TEN));

        assertThat(service.payPending().paid()).isEqualTo(1);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).save(eq(Topics.BONUS_GRANTED), anyString(), payload.capture());
        BonusGrantedEvent event = (BonusGrantedEvent) payload.getValue();
        assertThat(event.bizNo()).isEqualTo("REBATE-900");
        assertThat(event.userLine()).isEqualTo(2);
        assertThat(event.bonusType()).isEqualTo(REBATE);
        assertThat(event.turnoverMultiplier()).isEqualByComparingTo("3");
        assertThat(event.turnoverScope()).isEqualTo("GAME_TYPE");
        assertThat(event.turnoverScopeValue()).isEqualTo("SLOT");
    }

    /** REBATE promotions the mapper returns for DAY (business zone +08:00). */
    private void programmes(Promotion... rows) {
        when(promotionMapper.selectOnlineOverlapping(REBATE, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(rows));
    }

    private void validBets(ValidBetDaily... rows) {
        List<Long> users = Arrays.stream(rows).map(ValidBetDaily::getUserId).distinct().sorted().toList();
        when(validBetMapper.selectUserIds(eq(DAY), eq(0L), anyInt())).thenReturn(users);
        when(validBetMapper.selectByUsers(DAY, users)).thenReturn(List.of(rows));
    }

    private static ValidBetDaily row(long userId, int line, String currency, String provider, String validBet,
                                     int updatedMinutesLater) {
        ValidBetDaily row = new ValidBetDaily();
        row.setStatDate(DAY);
        row.setUserId(userId);
        row.setUserLine(line);
        row.setCurrency(currency);
        row.setProviderCode(provider);
        row.setValidBet(new BigDecimal(validBet));
        row.setRoundCount(1);
        row.setUpdatedAt(DAY.atTime(10, 0).plusMinutes(updatedMinutesLater));
        return row;
    }

    private RebateRecord record(long userId, String currency) {
        return inserted.stream()
                .filter(r -> r.getUserId() == userId && r.getCurrency().equals(currency))
                .findFirst()
                .orElseThrow();
    }
}
