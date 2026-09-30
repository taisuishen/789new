package com.bingo789.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param ttl               session lifetime; refreshed by GET /api/user/me unless the player set a session limit
 * @param maxFailedLogins   wrong passwords per username before login is locked for {@code failedLoginWindow}
 * @param failedLoginWindow lock-out window, counted from the first failure
 */
@ConfigurationProperties("bingo.session")
public record SessionProperties(
        @DefaultValue("12h") Duration ttl,
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("15m") Duration failedLoginWindow) {
}
