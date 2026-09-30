package com.bingo789.user.api.dto;

import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;

import java.time.Instant;

/**
 * Compliance gate evaluated by user-service; callers must not re-derive these flags themselves.
 *
 * @param userLine    the player's current line; stamp it on rows the caller writes for this player
 * @param canPlay     account active, not self-excluded / cooling off, KYC level sufficient for play
 * @param canDeposit  account active, not self-excluded
 * @param canWithdraw account not closed, KYC verified (self-excluded players may still withdraw)
 * @param reason      first failed check, for display and audit
 */
public record PlayerStatusView(
        long userId,
        int userLine,
        String defaultCurrency,
        AccountStatus accountStatus,
        KycStatus kycStatus,
        boolean canPlay,
        boolean canDeposit,
        boolean canWithdraw,
        Instant selfExcludedUntil,
        String reason) {
}
