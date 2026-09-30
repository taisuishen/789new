package com.bingo789.user.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "must be 4-32 letters, digits or underscores")
        String username,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$", message = "must be in E.164 format") String phone,
        @NotNull @Past LocalDate dateOfBirth,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String countryCode,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be an ISO 4217 code") String currency,
        /** Upline agent from the referral link; optional. */
        @Positive Long agentId,
        /** Registration channel / campaign code from the landing page; optional. */
        @Pattern(regexp = "^[A-Za-z0-9_.-]{1,32}$", message = "must be 1-32 letters, digits, '_', '.' or '-'")
        String registerChannel) {

    /** Keeps the password out of logs. */
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", countryCode=" + countryCode + ", currency=" + currency
                + ", agentId=" + agentId + ", registerChannel=" + registerChannel + "]";
    }
}
