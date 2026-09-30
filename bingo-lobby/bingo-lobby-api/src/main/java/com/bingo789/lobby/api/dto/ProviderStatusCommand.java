package com.bingo789.lobby.api.dto;

import com.bingo789.lobby.api.enums.ProviderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Automatic status change from game-integration: only ACTIVE and AUTO_MAINTENANCE are accepted. */
public record ProviderStatusCommand(
        @NotBlank String providerCode,
        @NotNull ProviderStatus status,
        String reason) {
}
