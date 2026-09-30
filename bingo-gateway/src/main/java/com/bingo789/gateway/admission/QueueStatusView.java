package com.bingo789.gateway.admission;

import java.time.Instant;

/**
 * Body data of {@code GET /api/queue/status}. Once {@code position} is 0 the answer carries a pass: retry the
 * protected request with header {@code X-Admission-Pass: <pass>}. When the waiting room is disabled, position is 0
 * and no pass is needed (pass is null): retry directly.
 */
public record QueueStatusView(long position, int retryAfterSeconds, String pass, Instant passExpiresAt) {
}
