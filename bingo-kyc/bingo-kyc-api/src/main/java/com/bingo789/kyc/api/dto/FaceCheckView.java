package com.bingo789.kyc.api.dto;

import java.math.BigDecimal;

/**
 * @param success false when no comparison was possible (no face, download failure, both services down)
 * @param match   same person (distance <= threshold); false when {@code success} is false
 * @param source  POD (facecmp) or SERVERLESS (fallback)
 * @param reason  English reason when {@code success} is false
 */
public record FaceCheckView(
        boolean success,
        boolean match,
        BigDecimal distance,
        BigDecimal threshold,
        String source,
        String reason) {
}
