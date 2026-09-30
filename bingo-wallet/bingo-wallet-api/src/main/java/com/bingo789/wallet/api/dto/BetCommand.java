package com.bingo789.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Debit a stake. Idempotency key: (providerCode, providerTxnId, BET). */
public record BetCommand(
        @NotNull Long userId,
        @NotBlank String currency,
        @NotBlank String providerCode,
        @NotBlank String providerTxnId,
        @NotBlank String roundId,
        String gameCode,
        @NotNull BigDecimal amount,
        boolean roundClosed) {
}
