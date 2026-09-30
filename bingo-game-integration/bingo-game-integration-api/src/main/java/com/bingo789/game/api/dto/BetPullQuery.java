package com.bingo789.game.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** One page of a provider bet-history pull for the window [from, to). */
public record BetPullQuery(
        @NotBlank String providerCode,
        @NotNull Instant from,
        @NotNull Instant to,
        String cursor,
        int pageSize) {
}
