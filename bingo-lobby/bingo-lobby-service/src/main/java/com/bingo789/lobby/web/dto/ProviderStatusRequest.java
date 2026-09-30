package com.bingo789.lobby.web.dto;

import com.bingo789.lobby.api.enums.ProviderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Operator status change: ACTIVE, MAINTENANCE or DISABLED (AUTO_MAINTENANCE is reserved for automation). */
public record ProviderStatusRequest(@NotNull ProviderStatus status, @Size(max = 255) String reason) {
}
