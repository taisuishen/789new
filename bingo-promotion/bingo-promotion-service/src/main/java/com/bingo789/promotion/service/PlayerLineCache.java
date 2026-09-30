package com.bingo789.promotion.service;

import com.bingo789.common.core.RetryableException;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Current line of a player for display purposes (the activity list), cached per pod for {@code user-line-cache-ttl}:
 * after a line migration the player may see the old line's activities for that long. Failures are not cached.
 * Money decisions never use this cache: they use the line carried by the triggering event or row.
 * <p>
 * Also answers whether the player may be shown marketing at all: self-excluded / cooling-off players, suspended or
 * closed accounts and shadows see no promotions (responsible gaming); a missing KYC step alone does not hide them.
 */
@Slf4j
@Component
public class PlayerLineCache {

    private static final long MAX_ENTRIES = 200_000;
    /** PlayerStatusView.reason for a missing KYC step, e.g. KYC_NONE. */
    private static final String KYC_REASON_PREFIX = "KYC_";

    private final UserClient userClient;
    private final Cache<Long, Audience> cache;

    /** @param marketable false for players who must not be shown promotions */
    public record Audience(int line, boolean marketable) {
    }

    public PlayerLineCache(UserClient userClient, PromotionProperties properties) {
        this.userClient = userClient;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.userLineCacheTtl())
                .maximumSize(MAX_ENTRIES)
                .build();
    }

    public int lineOf(long userId) {
        return audienceOf(userId).line();
    }

    public Audience audienceOf(long userId) {
        try {
            return cache.get(userId, id -> {
                PlayerStatusView status = userClient.playerStatus(id);
                // a user-service without the field sends 0: that player is on the default line
                return new Audience(UserLine.orDefault(status.userLine()), marketable(status));
            });
        } catch (RuntimeException e) {
            log.warn("line of player {} unavailable", userId, e);
            throw new RetryableException("player status temporarily unavailable");
        }
    }

    /** Only a KYC gap keeps a player marketable; every other reason (RG restriction, account state) hides promotions. */
    static boolean marketable(PlayerStatusView status) {
        return status.reason() == null || status.reason().startsWith(KYC_REASON_PREFIX);
    }
}
