package com.bingo789.kyc.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Back-office view of one submission. Images are returned as short-lived signed URLs, never stored.
 *
 * @param status   0 待提交, 1 处理中, 2 成功, 3 拒绝, 4 RunPod 响应失败
 * @param decision worker decision (approved / rejected / error), null while open
 */
public record KycSubmissionView(
        long id,
        long userId,
        int userLine,
        int identityType,
        int status,
        String decision,
        List<String> reasons,
        List<String> reasonsEn,
        String gender,
        BigDecimal faceDistance,
        BigDecimal faceThreshold,
        int submitAttempts,
        String lastError,
        String idFrontUrl,
        String idBackUrl,
        String selfieUrl,
        Instant createdAt,
        Instant completedAt) {
}
