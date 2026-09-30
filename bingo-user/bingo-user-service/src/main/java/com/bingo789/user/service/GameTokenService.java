package com.bingo789.user.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.session.SessionKeys;
import com.bingo789.user.UserErrorCode;
import com.bingo789.user.api.dto.GameTokenView;
import com.bingo789.user.api.dto.IssueGameTokenCommand;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.config.ComplianceProperties;
import com.bingo789.user.config.GameTokenProperties;
import com.bingo789.user.support.Tokens;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/** Launch tokens that providers present back in their "authenticate" callback. */
@Service
@RequiredArgsConstructor
public class GameTokenService {

    private final StringRedisTemplate redis;
    private final PlayerStatusService playerStatusService;
    private final ComplianceProperties compliance;
    private final GameTokenProperties properties;
    private final Clock clock;

    /**
     * Callers should check playerStatus first for a friendly message; this is the backstop.
     *
     * @throws BizException PLAY_NOT_ALLOWED (HTTP 403) when the player may not play
     */
    public GameTokenView issue(IssueGameTokenCommand command) {
        PlayerStatusView status = playerStatusService.status(command.userId());
        if (!status.canPlay()) {
            throw new BizException(UserErrorCode.PLAY_NOT_ALLOWED, "play not allowed: " + status.reason());
        }
        BizException.check(compliance.isCurrencyAllowed(command.currency()), UserErrorCode.CURRENCY_NOT_SUPPORTED);
        // command.clientIp is deliberately not bound to the token: providers present it from their own servers.
        String token = Tokens.newToken();
        GameTokenView view = new GameTokenView(true, token, command.userId(), command.providerCode(), command.gameCode(),
                command.currency(), clock.instant().plus(properties.ttl()));
        redis.opsForValue().set(SessionKeys.gameToken(token), JsonUtils.toJson(view), properties.ttl());
        return view;
    }

    /**
     * Re-checks the compliance gate, so a self-exclusion or suspension after launch invalidates outstanding tokens
     * (there is no per-user index of game tokens to revoke them eagerly).
     */
    public GameTokenView verify(String token) {
        if (!Tokens.isWellFormed(token)) {
            return GameTokenView.invalid();
        }
        String json = redis.opsForValue().get(SessionKeys.gameToken(token));
        if (json == null) {
            return GameTokenView.invalid();
        }
        GameTokenView view = JsonUtils.fromJson(json, GameTokenView.class);
        Instant now = clock.instant();
        if (view.expiresAt() == null || !view.expiresAt().isAfter(now) || view.userId() == null) {
            return GameTokenView.invalid();
        }
        try {
            return playerStatusService.status(view.userId()).canPlay() ? view : GameTokenView.invalid();
        } catch (BizException e) {
            return GameTokenView.invalid(); // unknown user
        }
    }
}
