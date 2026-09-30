package com.bingo789.user.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 32) String username,
        @NotBlank @Size(max = 128) String password) {

    /** Keeps the password out of logs. */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}
