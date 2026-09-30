package com.bingo789.game.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Asks game-integration to query the provider about a long-open round and settle or refund it. */
public record ResolveRoundCommand(
        @NotBlank String providerCode,
        @NotBlank String roundId,
        @NotNull Long userId,
        @NotBlank String currency) {
}
