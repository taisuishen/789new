package com.bingo789.user.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param targetLine line the player moves to
 * @param operatorId back-office operator who ordered the move (audit)
 */
public record MigrateUserLineCommand(
        @Min(1) @Max(99) int targetLine,
        @NotBlank @Size(max = 64) String operatorId,
        @Size(max = 255) String reason) {
}
