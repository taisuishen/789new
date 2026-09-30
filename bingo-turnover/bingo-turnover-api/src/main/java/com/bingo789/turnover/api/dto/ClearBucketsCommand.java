package com.bingo789.turnover.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param bucketId one bucket, or null for every ACTIVE bucket of {@code currency} */
public record ClearBucketsCommand(
        Long bucketId,
        @NotBlank @Size(max = 8) String currency,
        @NotBlank @Size(max = 64) String operatorId,
        @Size(max = 255) String reason) {
}
