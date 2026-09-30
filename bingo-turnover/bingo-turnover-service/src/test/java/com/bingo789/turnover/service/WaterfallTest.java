package com.bingo789.turnover.service;

import com.bingo789.common.core.game.TurnoverScope;
import com.bingo789.turnover.domain.BucketStatus;
import com.bingo789.turnover.domain.CloseReason;
import com.bingo789.turnover.domain.SourceType;
import com.bingo789.turnover.domain.TurnoverBucket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class WaterfallTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final AtomicLong IDS = new AtomicLong();

    @Test
    void consumesGameThenGameTypeThenAllOldestFirst() {
        TurnoverBucket all = bucket(TurnoverScope.ALL, "", "100", T0);
        TurnoverBucket slotOld = bucket(TurnoverScope.GAME_TYPE, "SLOT", "30", T0);
        TurnoverBucket slotNew = bucket(TurnoverScope.GAME_TYPE, "SLOT", "30", T0.plusMinutes(1));
        TurnoverBucket game = bucket(TurnoverScope.GAME, "PG:fortune-tiger", "20", T0);
        List<TurnoverBucket> buckets = sorted(game, slotOld, slotNew, all);

        List<Waterfall.Take> takes = Waterfall.fill(buckets, round("PG", "fortune-tiger", "SLOT", "150"), null);

        assertThat(takes).extracting(t -> t.bucket().getId())
                .containsExactly(game.getId(), slotOld.getId(), slotNew.getId(), all.getId());
        assertThat(takes).extracting(t -> t.amount().toPlainString()).containsExactly("20", "30", "30", "70");
        assertThat(game.getStatus()).isEqualTo(BucketStatus.COMPLETED);
        assertThat(game.getCloseReason()).isEqualTo(CloseReason.FULFILLED);
        assertThat(all.getStatus()).isEqualTo(BucketStatus.ACTIVE);
        assertThat(all.remaining()).isEqualByComparingTo("30");
    }

    @Test
    void scopedBucketsOnlyTakeTheirGameOrType() {
        TurnoverBucket game = bucket(TurnoverScope.GAME, "PG:fortune-tiger", "20", T0);
        TurnoverBucket fishing = bucket(TurnoverScope.GAME_TYPE, "FISHING", "20", T0);
        TurnoverBucket all = bucket(TurnoverScope.ALL, "", "50", T0);
        List<TurnoverBucket> buckets = sorted(game, fishing, all);

        List<Waterfall.Take> takes = Waterfall.fill(buckets, round("JILI", "mega-slot", "SLOT", "10"), null);

        assertThat(takes).singleElement().satisfies(t -> {
            assertThat(t.bucket()).isSameAs(all);
            assertThat(t.amount()).isEqualByComparingTo("10");
        });
        assertThat(game.getAchievedAmount()).isEqualByComparingTo("0");
        assertThat(fishing.getAchievedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void roundsBetBeforeTheBucketExistedDoNotCount() {
        TurnoverBucket later = bucket(TurnoverScope.ALL, "", "50", T0.plusHours(1));
        List<Waterfall.Take> takes = Waterfall.fill(sorted(later), round("PG", "g", "SLOT", "10"), null);
        assertThat(takes).isEmpty();
        assertThat(later.getAchievedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void smallRemainderCompletesWhenConfigured() {
        TurnoverBucket all = bucket(TurnoverScope.ALL, "", "100", T0);
        Waterfall.fill(sorted(all), round("PG", "g", "SLOT", "99.5"), new BigDecimal("1"));
        assertThat(all.getStatus()).isEqualTo(BucketStatus.COMPLETED);
        assertThat(all.getCloseReason()).isEqualTo(CloseReason.REMAINING_BELOW_THRESHOLD);
    }

    @Test
    void lowBalanceClearsOnlyBucketsThatExistedAtSettlement() {
        TurnoverBucket before = bucket(TurnoverScope.ALL, "", "100", T0);
        TurnoverBucket slot = bucket(TurnoverScope.GAME_TYPE, "SLOT", "100", T0);
        TurnoverBucket afterSettlement = bucket(TurnoverScope.ALL, "", "100", T0.plusHours(2));
        TurnoverBucket otherCurrency = bucket(TurnoverScope.ALL, "", "100", T0);
        otherCurrency.setCurrency("USD");

        List<TurnoverBucket> cleared = Waterfall.clearForLowBalance(
                sorted(slot, before, afterSettlement, otherCurrency), round("PG", "g", "SLOT", "0"));

        assertThat(cleared).containsExactlyInAnyOrder(before, slot);
        assertThat(before.getCloseReason()).isEqualTo(CloseReason.BALANCE_BELOW_THRESHOLD);
        assertThat(afterSettlement.getStatus()).isEqualTo(BucketStatus.ACTIVE);
        assertThat(otherCurrency.getStatus()).isEqualTo(BucketStatus.ACTIVE);
    }

    /** Bet at T0 + 10 min, settled at T0 + 11 min. */
    private static SettledRound round(String provider, String gameCode, String gameType, String validBet) {
        return new SettledRound(7L, 1, "PHP", provider + ":r:7", provider, gameCode, provider + ":" + gameCode,
                gameType, new BigDecimal(validBet), new BigDecimal("1.00"), T0.plusMinutes(10), T0.plusMinutes(11));
    }

    private static TurnoverBucket bucket(TurnoverScope scope, String value, String required, LocalDateTime createdAt) {
        TurnoverBucket b = new TurnoverBucket();
        b.setId(IDS.incrementAndGet());
        b.setUserId(7L);
        b.setUserLine(1);
        b.setCurrency("PHP");
        b.setScopeType(scope);
        b.setScopeValue(value);
        b.setScopeRank(TurnoverBucket.rankOf(scope));
        b.setSourceType(SourceType.BONUS);
        b.setSourceNo("B" + b.getId());
        b.setRequiredAmount(new BigDecimal(required));
        b.setAchievedAmount(BigDecimal.ZERO);
        b.setStatus(BucketStatus.ACTIVE);
        b.setVersion(0);
        b.setCreatedAt(createdAt);
        return b;
    }

    /** Same order as TurnoverBucketMapper.selectActive. */
    private static List<TurnoverBucket> sorted(TurnoverBucket... buckets) {
        return java.util.Arrays.stream(buckets)
                .sorted(java.util.Comparator.comparing(TurnoverBucket::getScopeRank)
                        .thenComparing(TurnoverBucket::getCreatedAt)
                        .thenComparing(TurnoverBucket::getId))
                .toList();
    }
}
