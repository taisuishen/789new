package com.bingo789.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Idempotent: opening an existing wallet returns it unchanged. */
public record OpenWalletCommand(@NotNull Long userId, @NotBlank String currency) {
}
