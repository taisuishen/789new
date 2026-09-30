package com.bingo789.risk.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WalletHoldRequest(@NotBlank @Size(max = 200) String reason) {
}
