package com.bingo789.promotion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Programme terms (rates, caps, wagering) are not configured here: they live in the promotion table, per promotion.
 *
 * @param businessZone           time zone that defines a business day for valid bets and rebates
 * @param catalogRefreshInterval how often every pod reloads the ONLINE promotions from the database
 * @param userLineCacheTtl       how long a player's line is cached for the activity list
 */
@ConfigurationProperties("bingo.promotion")
public record PromotionProperties(
        @DefaultValue("+08:00") String businessZone,
        @DefaultValue("30s") Duration catalogRefreshInterval,
        @DefaultValue("30s") Duration userLineCacheTtl,
        @DefaultValue FirstDeposit firstDeposit) {

    public ZoneId zone() {
        return ZoneId.of(businessZone);
    }

    /**
     * @param enabled global kill switch in front of every FIRST_DEPOSIT promotion (see BonusGrantService)
     */
    public record FirstDeposit(
            @DefaultValue("false") boolean enabled) {
    }
}
