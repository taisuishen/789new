package com.bingo789.user.web.dto;

import java.time.Instant;

/** @param token opaque session token, sent back as {@code Authorization: Bearer <token>} */
public record LoginResponse(String token, Instant expiresAt, long userId) {
}
