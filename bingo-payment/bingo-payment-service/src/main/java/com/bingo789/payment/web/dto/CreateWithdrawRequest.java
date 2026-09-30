package com.bingo789.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param currency optional; defaults to the player's account currency
 * @param payeeRef token issued by the payee vault for a saved bank / e-wallet account. All-digit values are
 *                 refused so a raw account or card number can never be stored by mistake.
 */
public record CreateWithdrawRequest(
        @NotNull BigDecimal amount,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotBlank @Size(max = 32) String channelCode,
        @NotBlank
        @Pattern(regexp = "^(?!\\d+$)[A-Za-z0-9_-]{8,128}$",
                message = "must be a payee token from the payee vault, not a raw account number")
        String payeeRef) {
}
