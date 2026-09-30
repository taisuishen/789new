package com.bingo789.user.web.dto;

import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;

import java.time.Instant;
import java.time.LocalDate;

/** @param restriction first failed compliance check (same values as PlayerStatusView.reason), null when unrestricted */
public record MeResponse(
        long userId,
        String username,
        String email,
        String phone,
        LocalDate dateOfBirth,
        String countryCode,
        String defaultCurrency,
        AccountStatus status,
        KycStatus kycStatus,
        boolean canPlay,
        boolean canDeposit,
        boolean canWithdraw,
        String restriction,
        Instant selfExcludedUntil) {
}
