package com.bingo789.lobby.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.game.api.GameLaunchClient;
import com.bingo789.game.api.dto.LaunchCommand;
import com.bingo789.game.api.dto.LaunchView;
import com.bingo789.lobby.LobbyErrorCode;
import com.bingo789.lobby.api.enums.ProviderStatus;
import com.bingo789.lobby.config.LobbyProperties;
import com.bingo789.lobby.entity.Game;
import com.bingo789.lobby.entity.GameProvider;
import com.bingo789.lobby.entity.GameStatus;
import com.bingo789.lobby.mapper.GameMapper;
import com.bingo789.lobby.mapper.GameProviderMapper;
import com.bingo789.lobby.web.dto.LaunchRequest;
import com.bingo789.lobby.web.dto.LaunchResponse;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.GameTokenView;
import com.bingo789.user.api.dto.IssueGameTokenCommand;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.BalanceView;
import com.bingo789.wallet.api.enums.WalletStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Game launch gate. Checks run cheapest-first and every downstream failure blocks the launch:
 * a launch is never allowed on an unknown compliance or wallet state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LaunchService {

    private static final String MOBILE = "MOBILE";
    private static final String DESKTOP = "DESKTOP";

    private final GameMapper gameMapper;
    private final GameProviderMapper providerMapper;
    private final UserClient userClient;
    private final WalletClient walletClient;
    private final GameLaunchClient gameLaunchClient;
    private final LobbyProperties properties;

    public LaunchResponse launch(long userId, long gameId, LaunchRequest request, String clientIp) {
        // authoritative status from the database, not the browsing cache
        Game game = gameMapper.selectById(gameId);
        BizException.check(game != null, LobbyErrorCode.GAME_NOT_FOUND);
        BizException.check(game.getStatus() == GameStatus.ONLINE, LobbyErrorCode.GAME_UNAVAILABLE);
        GameProvider provider = providerMapper.selectById(game.getProviderCode());
        BizException.check(provider != null && provider.getStatus() == ProviderStatus.ACTIVE, LobbyErrorCode.PROVIDER_UNAVAILABLE);
        String platform = request.platform();
        BizException.check(supports(game, platform), LobbyErrorCode.PLATFORM_NOT_SUPPORTED);

        PlayerStatusView player = remote("user", () -> userClient.playerStatus(userId));
        if (!player.canPlay()) {
            log.info("launch rejected: user {} cannot play ({})", userId, player.reason());
            throw new BizException(LobbyErrorCode.PLAYER_NOT_ALLOWED,
                    player.reason() != null ? player.reason() : LobbyErrorCode.PLAYER_NOT_ALLOWED.message());
        }
        String currency = request.currency() != null && !request.currency().isBlank()
                ? request.currency().trim().toUpperCase(Locale.ROOT)
                : player.defaultCurrency();
        BizException.check(currency != null && !currency.isBlank(), CommonErrorCode.BAD_REQUEST, "currency is required");

        // Documented degradation rule: when the wallet is down, the lobby blocks NEW game launches first,
        // so wallet capacity goes to settling rounds that are already in play. The probe has a short
        // Feign read timeout (walletClient) and any failure rejects the launch.
        BalanceView wallet = remote("wallet", () -> walletClient.balance(userId, currency));
        BizException.check(wallet.status() == WalletStatus.ACTIVE, LobbyErrorCode.WALLET_LOCKED);

        GameTokenView token = remote("user", () -> userClient.issueGameToken(new IssueGameTokenCommand(
                userId, game.getProviderCode(), game.getGameCode(), currency, clientIp)));
        BizException.check(token.valid() && token.token() != null, CommonErrorCode.SERVICE_UNAVAILABLE);

        LaunchView launch = remote("game-integration", () -> gameLaunchClient.launch(new LaunchCommand(
                userId, player.userLine(), game.getProviderCode(), game.getGameCode(), currency, token.token(), request.language(),
                platform, safeLobbyUrl(request.lobbyUrl()), clientIp, false)));
        BizException.check(launch.url() != null, CommonErrorCode.SERVICE_UNAVAILABLE);
        log.info("game launched: user={}, provider={}, game={}, currency={}", userId, game.getProviderCode(), game.getGameCode(), currency);
        return new LaunchResponse(launch.url(), launch.walletMode());
    }

    private static boolean supports(Game game, String platform) {
        if (MOBILE.equals(platform)) {
            return !Boolean.FALSE.equals(game.getMobileSupported());
        }
        if (DESKTOP.equals(platform)) {
            return !Boolean.FALSE.equals(game.getDesktopSupported());
        }
        return true;
    }

    /** Only lobby URLs on allowed hosts are passed to the provider; anything else falls back to the default. */
    private String safeLobbyUrl(String requested) {
        if (requested == null || requested.isBlank()) {
            return properties.defaultLobbyUrl();
        }
        try {
            URI uri = URI.create(requested.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && properties.allowedLobbyHosts().contains(host.toLowerCase(Locale.ROOT))) {
                return uri.toString();
            }
        } catch (IllegalArgumentException e) {
            // malformed URL: fall through to the default
        }
        return properties.defaultLobbyUrl();
    }

    /** Any failure or empty answer of a downstream dependency blocks the launch with 503. */
    private static <T> T remote(String dependency, Supplier<T> call) {
        T result;
        try {
            result = call.get();
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("launch blocked: {} unavailable: {}", dependency, e.toString());
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        if (result == null) {
            log.warn("launch blocked: empty response from {}", dependency);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return result;
    }
}
