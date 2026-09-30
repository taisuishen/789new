package com.bingo789.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Provider re-settlement or manual correction. Always a new row that references the original,
 * never an edit. Negative adjustments follow {@code bingo.wallet.negative-balance-policy}.
 */
public record AdjustCommand(
        @NotNull Long userId,
        @NotBlank String currency,
        @NotBlank String providerCode,
        @NotBlank String providerTxnId,
        String refTxnId,
        String roundId,
        @NotNull BigDecimal signedAmount,
        String reason) {
}
