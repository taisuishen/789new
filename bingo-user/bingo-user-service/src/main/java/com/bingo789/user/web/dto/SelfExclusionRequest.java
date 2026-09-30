package com.bingo789.user.web.dto;

import jakarta.validation.constraints.Positive;

/**
 * Either {@code periodDays} or {@code permanent = true}. A self-exclusion can be extended but never shortened
 * or revoked by the player.
 */
public record SelfExclusionRequest(@Positive Integer periodDays, boolean permanent) {
}
