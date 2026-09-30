package com.bingo789.turnover.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param ticketNo   idempotency key of the manual action (e.g. the back-office ticket)
 * @param scopeType  GAME, GAME_TYPE or ALL; null = ALL
 * @param scopeValue see {@link BucketView#scopeValue()}
 * @param required   wagering required, in {@code currency}
 */
public record ManualBucketCommand(
        @NotBlank @Size(max = 64) String ticketNo,
        @NotBlank @Size(max = 8) String currency,
        String scopeType,
        @Size(max = 128) String scopeValue,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal required,
        @NotBlank @Size(max = 64) String operatorId,
        @Size(max = 255) String reason) {
}
