package com.bingo789.wallet.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Copies the player's line (owned by user-service) onto the wallet, so every later ledger row carries it.
 * Idempotent and order-safe: a {@code version} not newer than the stored one is ignored.
 */
public record UpdateUserLineCommand(
        @NotNull Long userId,
        @Min(1) @Max(99) int userLine,
        @Min(1) long version) {
}
