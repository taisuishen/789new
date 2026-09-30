package com.bingo789.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record IssueGameTokenCommand(
        @NotNull Long userId,
        @NotBlank String providerCode,
        @NotBlank String gameCode,
        @NotBlank String currency,
        String clientIp) {
}
