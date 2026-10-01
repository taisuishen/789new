package com.bingo789.game.wallet;

import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.GameTokenView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Game tokens (issued by user-service at launch) verified for providers that present the token on EVERY callback
 * (WalletCommand.Session). A valid token is cached for {@link #VALID_TTL}: one user-service call per player and half
 * minute instead of one per bet. A self-exclusion or suspension (GameTokenView.playAllowed, re-checked by user-service
 * on every verification) therefore stops the stakes of token-per-call providers within that time, while wins and
 * refunds keep being credited. Invalid tokens are not cached, so a player whose token was just issued is never
 * locked out by an earlier miss.
 */
@Component
public class GameSessions {

    static final Duration VALID_TTL = Duration.ofSeconds(30);

    private final UserClient users;
    private final Cache<String, GameTokenView> valid = Caffeine.newBuilder()
            .expireAfterWrite(VALID_TTL)
            .maximumSize(500_000)
            .build();

    public GameSessions(UserClient users) {
        this.users = users;
    }

    /** @return the token's session, or an invalid view (unknown, expired, other provider, player may not play) */
    public GameTokenView verify(String providerCode, String token) {
        if (token == null || token.isBlank()) {
            return GameTokenView.invalid();
        }
        GameTokenView cached = valid.getIfPresent(token);
        GameTokenView view = cached != null ? cached : users.verifyGameToken(token);
        if (!view.valid() || !providerCode.equals(view.providerCode())) {
            return GameTokenView.invalid();
        }
        if (cached == null) {
            valid.put(token, view);
        }
        return view;
    }
}
