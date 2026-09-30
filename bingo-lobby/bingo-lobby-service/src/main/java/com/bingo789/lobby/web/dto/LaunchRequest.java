package com.bingo789.lobby.web.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param currency wallet currency to play with; defaults to the player's default currency
 * @param platform DESKTOP or MOBILE
 * @param lobbyUrl where the provider sends the player back; must point to an allowed host
 */
public record LaunchRequest(
        @Size(max = 8) String currency,
        @Size(max = 16) String language,
        @Pattern(regexp = "DESKTOP|MOBILE") String platform,
        @Size(max = 512) String lobbyUrl) {
}
