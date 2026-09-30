package com.bingo789.promotion.terms;

import com.bingo789.promotion.domain.PromotionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.bingo789.promotion.PromotionFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionTermsTest {

    @Test
    void firstDepositTermsParseWithScopedWagering() {
        FirstDepositTerms terms = FirstDepositTerms.parse(json("""
                {"percent":100,"maxAmount":1000,"currencies":["PHP"],
                 "turnover":{"multiplier":10,"scope":"GAME_TYPE","scopeValue":"SLOT"},
                 "display":{"title":"Welcome"}}"""));

        assertThat(terms.percent()).isEqualByComparingTo("100");
        assertThat(terms.maxAmount()).isEqualByComparingTo("1000");
        assertThat(terms.appliesTo("PHP")).isTrue();
        assertThat(terms.appliesTo("USD")).isFalse();
        assertThat(terms.turnover()).isEqualTo(new TurnoverTerms(new BigDecimal("10"), "GAME_TYPE", "SLOT"));
    }

    @Test
    void currenciesAndScopeAreOptional() {
        FirstDepositTerms terms = FirstDepositTerms.parse(json("""
                {"percent":50,"maxAmount":200,"turnover":{"multiplier":0}}"""));

        assertThat(terms.currencies()).isNull();
        assertThat(terms.appliesTo("USD")).isTrue();
        assertThat(terms.turnover().scope()).isEqualTo("ALL");
        assertThat(terms.turnover().scopeValue()).isNull();
    }

    @Test
    void rebateTermsUseTheProviderRateElseTheDefault() {
        RebateTerms terms = RebateTerms.parse(json("""
                {"defaultRate":0.005,"defaultDailyCap":null,"providers":{"DEMO":{"rate":0.008,"dailyCap":100},"PG":{"rate":0}},
                 "turnover":{"multiplier":1,"scope":"GAME","scopeValue":"DEMO:bingo-90"}}"""));

        assertThat(terms.rebate("DEMO", new BigDecimal("1000"))).isEqualByComparingTo("8");
        assertThat(terms.rebate("DEMO", new BigDecimal("20000"))).isEqualByComparingTo("100");
        assertThat(terms.rebate("PG", new BigDecimal("1000"))).isEqualByComparingTo("0");
        assertThat(terms.rebate("JILI", new BigDecimal("1000"))).isEqualByComparingTo("5");
        assertThat(terms.turnover()).isEqualTo(new TurnoverTerms(BigDecimal.ONE, "GAME", "DEMO:bingo-90"));
    }

    @Test
    void withoutADefaultRateUnlistedProvidersEarnNothing() {
        RebateTerms terms = RebateTerms.parse(json("""
                {"providers":{"DEMO":{"rate":0.01}},"turnover":{"multiplier":1}}"""));

        assertThat(terms.rebate("JILI", new BigDecimal("1000"))).isEqualByComparingTo("0");
        assertThat(terms.rebate("DEMO", new BigDecimal("1000"))).isEqualByComparingTo("10");
    }

    @Test
    void invalidConfigsNameTheSetting() {
        assertInvalid(PromotionType.FIRST_DEPOSIT, "[1]", "must be a JSON object");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000,"turnover":{"multiplier":10},"display":"banner"}""", "config.display");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"turnover":{"multiplier":10}}""", "config.maxAmount is required");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":0,"maxAmount":1000,"turnover":{"multiplier":10}}""", "config.percent");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000.00001,"turnover":{"multiplier":10}}""", "config.maxAmount");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000,"currencies":[],"turnover":{"multiplier":10}}""", "config.currencies");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000}""", "config.turnover is required");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000,"turnoverMultiplier":10,"turnover":{"multiplier":10}}""",
                "config.turnoverMultiplier is not a known setting");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000,"turnover":{"multiplier":-1}}""", "config.turnover.multiplier");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000,"turnover":{"multiplier":1,"scope":"GAME_TYPE","scopeValue":"CARDS"}}""",
                "unknown game type CARDS");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000,"turnover":{"multiplier":1,"scope":"ALL","scopeValue":"SLOT"}}""",
                "scope ALL takes no value");
        assertInvalid(PromotionType.FIRST_DEPOSIT, """
                {"percent":100,"maxAmount":1000,"turnover":{"multiplier":1,"scope":"GAME","scopeValue":"fortune-tiger"}}""",
                "PROVIDER:GAME_CODE");
        assertInvalid(PromotionType.REBATE, """
                {"turnover":{"multiplier":1}}""", "needs a defaultRate or at least one provider rate");
        assertInvalid(PromotionType.REBATE, """
                {"defaultRate":1.5,"turnover":{"multiplier":1}}""", "config.defaultRate");
        assertInvalid(PromotionType.REBATE, """
                {"defaultRate":0.005,"defaultCap":10,"turnover":{"multiplier":1}}""", "config.defaultCap is not a known setting");
        assertInvalid(PromotionType.REBATE, """
                {"defaultDailyCap":10,"providers":{"DEMO":{"rate":0.01}},"turnover":{"multiplier":1}}""", "needs a defaultRate");
        assertInvalid(PromotionType.REBATE, """
                {"providers":{"DEMO":{"cap":5}},"turnover":{"multiplier":1}}""", "config.providers.DEMO.cap");
        assertInvalid(PromotionType.REBATE, """
                {"providers":{"DEMO":{"dailyCap":5}},"turnover":{"multiplier":1}}""", "config.providers.DEMO.rate is required");
        assertInvalid(PromotionType.REBATE, """
                {"defaultRate":0.0000001,"turnover":{"multiplier":1}}""", "config.defaultRate");
    }

    @Test
    void displayIsOptional() {
        PromotionTerms.validate(PromotionType.REBATE, json("""
                {"defaultRate":0.005,"turnover":{"multiplier":1},"display":null}"""));
        PromotionTerms.validate(PromotionType.REBATE, json("""
                {"defaultRate":0.005,"turnover":{"multiplier":1},"display":{"title":"Daily rebate","rules":["a","b"]}}"""));
    }

    private static void assertInvalid(PromotionType type, String config, String messagePart) {
        assertThatThrownBy(() -> PromotionTerms.validate(type, json(config)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }
}
