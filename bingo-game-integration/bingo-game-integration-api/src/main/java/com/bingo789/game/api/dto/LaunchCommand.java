package com.bingo789.game.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param userLine  the player's line (PlayerStatusView.userLine), stamped on transfer orders
 * @param gameToken issued by user-service right before launch; the provider presents it back in "authenticate"
 * @param platform  DESKTOP or MOBILE
 */
public record LaunchCommand(
        @NotNull Long userId,
        int userLine,
        @NotBlank String providerCode,
        @NotBlank String gameCode,
        @NotBlank String currency,
        @NotBlank String gameToken,
        String language,
        String platform,
        String lobbyUrl,
        String clientIp,
        boolean demo) {
}
