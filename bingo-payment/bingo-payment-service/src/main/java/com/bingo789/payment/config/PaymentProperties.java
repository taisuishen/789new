package com.bingo789.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/**
 * @param businessZone time zone that defines the player's day / week / month for responsible-gaming deposit limits
 */
@ConfigurationProperties("bingo.payment")
public record PaymentProperties(
        @DefaultValue("+08:00") String businessZone,
        @DefaultValue Deposit deposit,
        @DefaultValue Withdraw withdraw,
        @DefaultValue MockChannel mockChannel) {

    public ZoneId zone() {
        return ZoneId.of(businessZone);
    }

    /**
     * @param recoverAfter unsettled orders older than this are queried at the channel
     * @param expireAfter  orders still unpaid after this are expired (a payment that still arrives is credited)
     */
    public record Deposit(
            @DefaultValue("5m") Duration recoverAfter,
            @DefaultValue("2h") Duration expireAfter) {
    }

    /**
     * @param recoverAfter     orders idle in a non-final state for this long are resumed by the recovery job
     * @param payingAlertAfter unresolved payouts older than this raise an alert for manual reconciliation
     */
    public record Withdraw(
            @DefaultValue("2m") Duration recoverAfter,
            @DefaultValue("24h") Duration payingAlertAfter) {
    }

    /** Local development channel; must stay disabled in production. */
    public record MockChannel(
            @DefaultValue("false") boolean enabled,
            String secret) {
    }
}
