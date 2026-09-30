package com.bingo789.turnover.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** See {@link TurnoverSettingView}; a null threshold switches that rule off. */
public record UpdateTurnoverSettingCommand(
        @Min(1) @Max(99) int userLine,
        @NotBlank @Size(max = 8) String currency,
        @DecimalMin(value = "0.0001") BigDecimal clearBelowBalance,
        @DecimalMin(value = "0.0001") BigDecimal completeBelowRemaining,
        @NotNull @DecimalMin(value = "0") BigDecimal depositMultiplier,
        @NotBlank @Size(max = 64) String operatorId) {
}
