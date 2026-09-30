package com.bingo789.wallet.api.dto;

import com.bingo789.wallet.api.enums.WalletStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Places or releases a wallet lock. Locks are keyed by {@code reason}: BET_LOCKED / FROZEN places (or updates) the
 * lock with that reason, ACTIVE releases it. The wallet's effective status is the strictest remaining lock, so one
 * team releasing its lock never lifts another team's (e.g. an RG expiry cannot lift an AML hold).
 * <p>
 * Used by user-service (SELF_EXCLUSION, COOL_OFF, KYC) and risk-service (AML_HOLD). Takes effect on the very next
 * provider callback, which is what makes self-exclusion immediate even for game sessions that are already open.
 *
 * @param currency null applies to all currencies of the user, including wallets opened later
 * @param reason   stable lock key; use the same value to release
 */
public record UpdateWalletStatusCommand(
        @NotNull Long userId,
        String currency,
        @NotNull WalletStatus status,
        @NotBlank String reason) {
}
