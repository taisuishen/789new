package com.bingo789.user.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** "Take a break": a short, non-revocable play and deposit block. */
public record CoolOffRequest(@NotNull @Positive Integer hours) {
}
