package com.bingo789.promotion.terms;

import com.bingo789.common.core.Money;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Terms of a REBATE promotion, applied per provider line of valid_bet_daily: valid bet x rate, capped by the daily
 * cap (per player, currency and provider per day). A provider listed under "providers" uses its own rate and cap
 * (a null cap = no cap), every other provider the defaults; without defaultRate unlisted providers earn nothing;
 * rate 0 excludes a provider.
 * Config: {"defaultRate":0.005,"defaultDailyCap":null,"providers":{"DEMO":{"rate":0.008,"dailyCap":100}},
 * "turnover":{"multiplier":1}}.
 *
 * @param defaultRate     fraction of valid bet for unlisted providers, e.g. 0.005 = 0.5%; null = none
 * @param defaultDailyCap cap for unlisted providers; null = no cap
 * @param providers       provider code -> own rate and cap
 */
public record RebateTerms(BigDecimal defaultRate, BigDecimal defaultDailyCap, Map<String, ProviderRate> providers,
                          TurnoverTerms turnover) {

    private static final String PATH = "config";
    private static final Set<String> KEYS = Set.of(PromotionTerms.DISPLAY, "defaultRate", "defaultDailyCap", "providers", TurnoverTerms.KEY);
    private static final Set<String> PROVIDER_KEYS = Set.of("rate", "dailyCap");
    /** Rates keep the precision of the former rebate_rule.rate DECIMAL(8,6). */
    private static final int RATE_SCALE = 6;
    /** valid_bet_daily.provider_code is VARCHAR(32). */
    private static final int MAX_PROVIDER_CODE = 32;

    /** @param dailyCap null = no cap */
    public record ProviderRate(BigDecimal rate, BigDecimal dailyCap) {
    }

    /** @throws IllegalArgumentException naming the first invalid setting */
    public static RebateTerms parse(JsonNode config) {
        ConfigReader.check(config != null && config.isObject(), PATH + " must be a JSON object");
        ConfigReader.onlyKeys(config, PATH, KEYS);
        BigDecimal defaultRate = rate(config, "defaultRate", PATH, false);
        BigDecimal defaultDailyCap = cap(config, "defaultDailyCap", PATH);
        ConfigReader.check(defaultDailyCap == null || defaultRate != null, PATH + ".defaultDailyCap needs a defaultRate");
        Map<String, ProviderRate> providers = new HashMap<>();
        JsonNode list = ConfigReader.object(config, "providers", PATH, false);
        if (list != null) {
            for (Map.Entry<String, JsonNode> entry : list.properties()) {
                String code = entry.getKey();
                String where = PATH + ".providers." + code;
                ConfigReader.check(!code.isBlank() && code.length() <= MAX_PROVIDER_CODE,
                        PATH + ".providers keys must be provider codes of at most " + MAX_PROVIDER_CODE + " characters");
                ConfigReader.check(entry.getValue().isObject(), where + " must be an object");
                ConfigReader.onlyKeys(entry.getValue(), where, PROVIDER_KEYS);
                providers.put(code, new ProviderRate(rate(entry.getValue(), "rate", where, true),
                        cap(entry.getValue(), "dailyCap", where)));
            }
        }
        ConfigReader.check(defaultRate != null || !providers.isEmpty(), PATH + " needs a defaultRate or at least one provider rate");
        return new RebateTerms(defaultRate, defaultDailyCap, Map.copyOf(providers), TurnoverTerms.parse(config, PATH));
    }

    /** Unrounded rebate of one provider line of a player's day; zero when no rate applies to the provider. */
    public BigDecimal rebate(String providerCode, BigDecimal validBet) {
        ProviderRate own = providerCode == null ? null : providers.get(providerCode);
        BigDecimal rate = own != null ? own.rate() : defaultRate;
        BigDecimal cap = own != null ? own.dailyCap() : defaultDailyCap;
        if (rate == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rebate = validBet.multiply(rate);
        return cap == null ? rebate : rebate.min(cap);
    }

    private static BigDecimal rate(JsonNode parent, String field, String path, boolean required) {
        BigDecimal rate = ConfigReader.decimal(parent, field, path, required, BigDecimal.ZERO, false, RATE_SCALE);
        ConfigReader.check(rate == null || rate.compareTo(BigDecimal.ONE) <= 0,
                path + "." + field + " is a fraction of valid bet and must be at most 1");
        return rate;
    }

    private static BigDecimal cap(JsonNode parent, String field, String path) {
        return ConfigReader.decimal(parent, field, path, false, BigDecimal.ZERO, false, Money.SCALE);
    }
}
