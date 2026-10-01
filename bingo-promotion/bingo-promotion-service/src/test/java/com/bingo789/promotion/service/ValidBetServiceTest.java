package com.bingo789.promotion.service;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.promotion.PromotionFixtures;
import com.bingo789.promotion.domain.PromotionRoundApplied;
import com.bingo789.promotion.domain.ValidBetDaily;
import com.bingo789.promotion.mapper.PromotionRoundAppliedMapper;
import com.bingo789.promotion.mapper.ValidBetDailyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValidBetServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    private ValidBetDailyMapper validBetMapper;
    private PromotionRoundAppliedMapper appliedMapper;
    private ValidBetService service;
    private final List<ValidBetDaily> upserted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        validBetMapper = mock(ValidBetDailyMapper.class);
        when(validBetMapper.upsertBatch(anyList())).thenAnswer(inv -> {
            List<ValidBetDaily> rows = inv.getArgument(0);
            upserted.addAll(rows);
            return rows.size();
        });
        appliedMapper = mock(PromotionRoundAppliedMapper.class);
        service = new ValidBetService(validBetMapper, appliedMapper, InlineTransactions.template(),
                PromotionFixtures.properties(false));
    }

    @Test
    void everyRowOfAUserDayTakesTheLineOfItsLatestRound() {
        service.applySettledRounds(List.of(
                round("DEMO", "r1", 7, 1, "10", at(DAY, 10)),
                round("JILI", "r2", 7, 1, "20", at(DAY, 11)),
                // player 7 migrated to line 2
                round("DEMO", "r3", 7, 2, "30", at(DAY, 12)),
                round("DEMO", "r4", 8, 1, "40", at(DAY, 12)),
                // next business day (00:30 UTC+8)
                round("DEMO", "r5", 7, 2, "50", LocalDateTime.of(2026, 9, 30, 0, 30).toInstant(BingoTime.ZONE))));

        assertThat(row(DAY, 7, "DEMO").getUserLine()).isEqualTo(2);
        assertThat(row(DAY, 7, "DEMO").getValidBet()).isEqualByComparingTo("40");
        assertThat(row(DAY, 7, "DEMO").getRoundCount()).isEqualTo(2);
        assertThat(row(DAY, 7, "JILI").getUserLine()).isEqualTo(2);
        assertThat(row(DAY, 8, "DEMO").getUserLine()).isEqualTo(1);
        assertThat(row(DAY.plusDays(1), 7, "DEMO").getUserLine()).isEqualTo(2);
    }

    @Test
    void aLaterRevisionAddsTheDifferenceAndAnOlderOneIsIgnored() {
        when(appliedMapper.selectExisting(anyCollection())).thenReturn(List.of(
                new PromotionRoundApplied("DEMO:r1:7", 1, new BigDecimal("10")),
                new PromotionRoundApplied("DEMO:r2:7", 3, new BigDecimal("20"))));
        when(appliedMapper.updateRevision(anyString(), anyInt(), any(), anyInt())).thenReturn(1);

        service.applySettledRounds(List.of(
                // r1 cancelled after it was counted: -10 and one round less
                revision("r1", "CANCELLED", "0", 2),
                // r2 already at revision 3: a re-sent revision 2 changes nothing
                revision("r2", "SETTLED", "5", 2)));

        assertThat(row(DAY, 7, "DEMO").getValidBet()).isEqualByComparingTo("-10");
        assertThat(row(DAY, 7, "DEMO").getRoundCount()).isEqualTo(-1);
        verify(appliedMapper).updateRevision("DEMO:r1:7", 2, BigDecimal.ZERO, 1);
        verify(appliedMapper, never()).updateRevision(eq("DEMO:r2:7"), anyInt(), any(), anyInt());
    }

    @Test
    void aRoundWithoutALineIsOnTheDefaultLine() {
        service.applySettledRounds(List.of(round("DEMO", "r1", 7, null, "10", at(DAY, 10))));

        assertThat(row(DAY, 7, "DEMO").getUserLine()).isEqualTo(1);
    }

    private static RoundSettledEvent round(String provider, String roundId, long userId, Integer line, String validBet,
                                           Instant settledAt) {
        BigDecimal amount = new BigDecimal(validBet);
        return new RoundSettledEvent(provider, roundId, userId, line, "PHP", "game-1", "SLOT", "Game 1", amount,
                BigDecimal.ZERO, amount, null, "SETTLED", settledAt.minusSeconds(30), settledAt, 1);
    }

    private static RoundSettledEvent revision(String roundId, String status, String validBet, int revision) {
        BigDecimal amount = new BigDecimal(validBet);
        Instant settledAt = at(DAY, 10);
        return new RoundSettledEvent("DEMO", roundId, 7, 1, "PHP", "game-1", "SLOT", "Game 1", amount,
                BigDecimal.ZERO, amount, null, status, settledAt.minusSeconds(30), settledAt, revision);
    }

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).toInstant(BingoTime.ZONE);
    }

    private ValidBetDaily row(LocalDate day, long userId, String provider) {
        return upserted.stream()
                .filter(r -> r.getStatDate().equals(day) && r.getUserId() == userId && r.getProviderCode().equals(provider))
                .findFirst()
                .orElseThrow();
    }
}
