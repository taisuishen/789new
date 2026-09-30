package com.bingo789.kyc.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param imageUrl OBS URL (or object key) of the new photo, uploaded by the player into their own KYC folder */
public record FaceCheckCommand(@NotBlank @Size(max = 2048) String imageUrl) {
}
