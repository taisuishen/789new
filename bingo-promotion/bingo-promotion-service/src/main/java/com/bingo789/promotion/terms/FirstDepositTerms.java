package com.bingo789.promotion.terms;

import com.bingo789.common.core.Money;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Terms of a FIRST_DEPOSIT promotion: bonus = min(deposit x percent / 100, maxAmount).
 * Config: {"percent":100,"maxAmount":1000,"currencies":["PHP"],"turnover":{"multiplier":10}}.
 *
 * @param percent    bonus as a percentage of the deposit (100 = 100%)
 * @param maxAmount  cap per bonus, in the deposit's currency (restrict {@code currencies} when that matters)
 * @param currencies deposit currencies the promotion applies to; null = every currency
 */
public record FirstDepositTerms(BigDecimal percent, BigDecimal maxAmount, Set<String> currencies, TurnoverTerms turnover) {

    private static final String PATH = "config";
    private static final Set<String> KEYS = Set.of(PromotionTerms.DISPLAY, "percent", "maxAmount", "currencies", TurnoverTerms.KEY);
    private static final Pattern CURRENCY = Pattern.compile("[A-Z0-9]{3,8}");

    /** @throws IllegalArgumentException naming the first invalid setting */
    public static FirstDepositTerms parse(JsonNode config) {
        ConfigReader.check(config != null && config.isObject(), PATH + " must be a JSON object");
        ConfigReader.onlyKeys(config, PATH, KEYS);
        BigDecimal percent = ConfigReader.decimal(config, "percent", PATH, true, BigDecimal.ZERO, true, Money.SCALE);
        BigDecimal maxAmount = ConfigReader.decimal(config, "maxAmount", PATH, true, BigDecimal.ZERO, true, Money.SCALE);
        Set<String> currencies = null;
        JsonNode list = ConfigReader.array(config, "currencies", PATH, false);
        if (list != null) {
            ConfigReader.check(!list.isEmpty(), PATH + ".currencies must not be empty (leave it out for every currency)");
            currencies = new HashSet<>();
            for (JsonNode item : list) {
                ConfigReader.check(item.isString() && CURRENCY.matcher(item.stringValue()).matches(),
                        PATH + ".currencies must hold currency codes such as \"PHP\"");
                currencies.add(item.stringValue());
            }
            currencies = Set.copyOf(currencies);
        }
        return new FirstDepositTerms(percent, maxAmount, currencies, TurnoverTerms.parse(config, PATH));
    }

    public boolean appliesTo(String currency) {
        // Set.copyOf sets throw on contains(null)
        return currencies == null || (currency != null && currencies.contains(currency));
    }
}
