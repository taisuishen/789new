package com.bingo789.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Single-call "debit and credit" used by many slot providers: stake and win are applied atomically
 * in one local transaction, recorded as two rows (BET + PAYOUT).
 */
public record BetAndPayoutCommand(
        @NotNull Long userId,
        @NotBlank String currency,
        @NotBlank String providerCode,
        @NotBlank String betTxnId,
        @NotBlank String payoutTxnId,
        @NotBlank String roundId,
        String gameCode,
        @NotNull BigDecimal betAmount,
        @NotNull BigDecimal payoutAmount,
        boolean roundClosed) {
}
