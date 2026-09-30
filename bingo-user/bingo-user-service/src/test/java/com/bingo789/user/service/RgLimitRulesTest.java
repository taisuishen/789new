package com.bingo789.user.service;

import com.bingo789.user.entity.UserRgSetting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RgLimitRulesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void loweringALimitAppliesImmediately() {
        RgLimitRules.Decision d = RgLimitRules.decide(amount("1000"), null, amount("500"));

        assertThat(d.current()).isEqualByComparingTo("500");
        assertThat(d.pending()).isNull();
        assertThat(d.newIncrease()).isFalse();
    }

    @Test
    void settingAFirstLimitIsATightening() {
        RgLimitRules.Decision d = RgLimitRules.decide(null, null, amount("100"));

        assertThat(d.current()).isEqualByComparingTo("100");
        assertThat(d.pending()).isNull();
    }

    @Test
    void raisingALimitIsQueued() {
        RgLimitRules.Decision d = RgLimitRules.decide(amount("500"), null, amount("1000"));

        assertThat(d.current()).isEqualByComparingTo("500");
        assertThat(d.pending()).isEqualByComparingTo("1000");
        assertThat(d.newIncrease()).isTrue();
    }

    @Test
    void removingALimitIsQueuedAsRemoval() {
        RgLimitRules.Decision d = RgLimitRules.decide(amount("500"), null, null);

        assertThat(d.current()).isEqualByComparingTo("500");
        assertThat(RgLimitRules.isRemoval(d.pending())).isTrue();
        assertThat(d.newIncrease()).isTrue();
    }

    @Test
    void resubmittingThePendingIncreaseKeepsTheCooldown() {
        RgLimitRules.Decision d = RgLimitRules.decide(amount("500"), amount("1000"), amount("1000"));

        assertThat(d.pending()).isEqualByComparingTo("1000");
        assertThat(d.newIncrease()).isFalse();
    }

    @Test
    void requestingTheCurrentLimitCancelsThePendingIncrease() {
        RgLimitRules.Decision d = RgLimitRules.decide(amount("500"), amount("1000"), amount("500"));

        assertThat(d.current()).isEqualByComparingTo("500");
        assertThat(d.pending()).isNull();
    }

    @Test
    void pendingIncreaseIsNotEffectiveBeforeTheCooldown() {
        UserRgSetting s = setting(amount("500"), amount("1000"), NOW.plusSeconds(1));

        RgLimitRules.Limits limits = RgLimitRules.effective(s, NOW);

        assertThat(limits.daily()).isEqualByComparingTo("500");
        assertThat(limits.pendingDaily()).isEqualByComparingTo("1000");
    }

    @Test
    void maturedIncreaseIsEffective() {
        UserRgSetting s = setting(amount("500"), amount("1000"), NOW);

        RgLimitRules.Limits limits = RgLimitRules.effective(s, NOW);

        assertThat(limits.daily()).isEqualByComparingTo("1000");
        assertThat(limits.pendingDaily()).isNull();
        assertThat(limits.pendingEffectiveAt()).isNull();
    }

    @Test
    void maturedRemovalClearsTheLimit() {
        UserRgSetting s = setting(amount("500"), RgLimitRules.REMOVE_LIMIT, NOW.minusSeconds(1));

        assertThat(RgLimitRules.effective(s, NOW).daily()).isNull();
    }

    private static UserRgSetting setting(BigDecimal daily, BigDecimal pendingDaily, Instant pendingAt) {
        UserRgSetting s = new UserRgSetting();
        s.setUserId(1L);
        s.setDailyDepositLimit(daily);
        s.setPendingDailyDepositLimit(pendingDaily);
        s.setPendingEffectiveAt(pendingAt);
        return s;
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value).setScale(4);
    }
}
