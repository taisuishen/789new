package com.bingo789.promotion.terms;

import com.bingo789.common.core.Money;
import com.bingo789.common.core.game.TurnoverScope;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Wagering requirement attached to a bonus or rebate: amount x multiplier must be wagered on the games in scope.
 * Config key "turnover": {"multiplier":10,"scope":"ALL","scopeValue":null}; scope optional (ALL). Copied onto the
 * grant / rebate row when it is created and published with BonusGrantedEvent.
 *
 * @param multiplier 0 = no requirement
 * @param scope      {@link TurnoverScope} name, never null
 * @param scopeValue GAME_TYPE: a game type name; GAME: "PROVIDER:GAME_CODE"; ALL: null
 */
public record TurnoverTerms(BigDecimal multiplier, String scope, String scopeValue) {

    static final String KEY = "turnover";
    /** turnover_multiplier columns are DECIMAL(10,4). */
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("999999.9999");
    /** turnover_scope_value columns are VARCHAR(128). */
    private static final int MAX_SCOPE_VALUE = 128;
    private static final Set<String> KEYS = Set.of("multiplier", "scope", "scopeValue");

    static TurnoverTerms parse(JsonNode config, String path) {
        JsonNode node = ConfigReader.object(config, KEY, path, true);
        String where = path + "." + KEY;
        ConfigReader.onlyKeys(node, where, KEYS);
        BigDecimal multiplier = ConfigReader.decimal(node, "multiplier", where, true, BigDecimal.ZERO, false, Money.SCALE);
        ConfigReader.check(multiplier.compareTo(MAX_MULTIPLIER) <= 0, where + ".multiplier must be at most " + MAX_MULTIPLIER);
        String scope = ConfigReader.text(node, "scope", where);
        String scopeValue = ConfigReader.text(node, "scopeValue", where);
        String error = TurnoverScope.validate(scope, scopeValue);
        ConfigReader.check(error == null, where + ": " + error);
        ConfigReader.check(scopeValue == null || scopeValue.length() <= MAX_SCOPE_VALUE,
                where + ".scopeValue allows at most " + MAX_SCOPE_VALUE + " characters");
        TurnoverScope parsed = scope == null ? TurnoverScope.ALL : TurnoverScope.valueOf(scope);
        return new TurnoverTerms(multiplier, parsed.name(), parsed == TurnoverScope.ALL ? null : scopeValue);
    }
}
