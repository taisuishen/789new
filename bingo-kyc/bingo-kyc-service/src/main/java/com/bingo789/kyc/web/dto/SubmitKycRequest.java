package com.bingo789.kyc.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Addresses as returned by POST /api/kyc/images (or the object keys).
 *
 * @param identityType declared document: 1 driver's licence, 23 PhilID (OCR is run for these two), 2 PhilHealth,
 *                     3 postal ID, 14 SSS, 15 TIN, 16 UMID, 17 voter's ID, 20 passport; other values: face match only
 */
public record SubmitKycRequest(
        @NotNull @Positive Integer identityType,
        @NotBlank @Size(max = 2048) String idFrontUrl,
        @Size(max = 2048) String idBackUrl,
        @NotBlank @Size(max = 2048) String selfieUrl) {
}
