package com.bingo789.user.api.dto;

import com.bingo789.user.api.enums.KycStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Sent by bingo-kyc: the player's KYC status after a submission changed state.
 *
 * @param reference the KYC submission (kyc_record id); results of an older submission never override a newer one
 */
public record UpdateKycStatusCommand(
        @NotNull KycStatus status,
        @NotBlank @Size(max = 128) String reference,
        @Size(max = 255) String reason) {
}
