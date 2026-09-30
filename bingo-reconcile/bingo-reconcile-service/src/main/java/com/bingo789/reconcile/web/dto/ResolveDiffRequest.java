package com.bingo789.reconcile.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** @param status RESOLVED (handled, e.g. manual adjustment booked) or IGNORED (accepted difference) */
public record ResolveDiffRequest(
        @NotBlank @Pattern(regexp = "RESOLVED|IGNORED") String status,
        @NotBlank @Size(max = 500) String note) {
}
