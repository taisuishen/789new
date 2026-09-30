package com.bingo789.promotion.terms;

import com.bingo789.promotion.domain.PromotionType;
import tools.jackson.databind.JsonNode;

/** config_json rules shared by every promotion type. */
public final class PromotionTerms {

    /** The only player-facing key of config_json. */
    public static final String DISPLAY = "display";

    private PromotionTerms() {
    }

    /**
     * Validates config_json for {@code type}: a JSON object, an optional "display" object, and the type's own terms
     * ({@link FirstDepositTerms}, {@link RebateTerms}).
     *
     * @throws IllegalArgumentException naming the first invalid setting
     */
    public static void validate(PromotionType type, JsonNode config) {
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("config must be a JSON object");
        }
        JsonNode display = config.get(DISPLAY);
        if (display != null && !display.isNull() && !display.isObject()) {
            throw new IllegalArgumentException("config." + DISPLAY + " must be an object");
        }
        switch (type) {
            case FIRST_DEPOSIT -> FirstDepositTerms.parse(config);
            case REBATE -> RebateTerms.parse(config);
        }
    }
}
