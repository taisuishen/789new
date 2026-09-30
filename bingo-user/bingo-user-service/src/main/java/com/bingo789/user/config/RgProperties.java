package com.bingo789.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Responsible-gaming rules.
 *
 * @param limitIncreaseCooldown delay before a deposit-limit increase (or removal) takes effect; decreases are immediate
 */
@ConfigurationProperties("bingo.rg")
public record RgProperties(
        @DefaultValue("24h") Duration limitIncreaseCooldown,
        @DefaultValue("24") int coolOffMinHours,
        @DefaultValue("1008") int coolOffMaxHours,
        @DefaultValue("180") int selfExclusionMinDays,
        @DefaultValue("1825") int selfExclusionMaxDays) {
}
