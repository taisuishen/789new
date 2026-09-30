package com.bingo789.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param currency optional; must equal the player's account currency when given
 */
public record CreateDepositRequest(
        @NotNull BigDecimal amount,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotBlank @Size(max = 32) String channelCode) {
}
