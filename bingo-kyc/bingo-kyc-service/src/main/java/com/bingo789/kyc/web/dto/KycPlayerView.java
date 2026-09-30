package com.bingo789.kyc.web.dto;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.kyc.domain.KycRecord;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;

/**
 * What the player sees of a submission (poll it after submitting).
 *
 * @param status  0 待提交 and 1 处理中 (still being verified), 2 成功, 3 拒绝, 4 RunPod 响应失败 (verification failed,
 *                submit again)
 * @param reasons English rejection reasons from the worker (never the internal ones)
 */
public record KycPlayerView(
        String id,
        int status,
        List<String> reasons,
        Instant createdAt,
        Instant completedAt) {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    public static KycPlayerView of(KycRecord record) {
        List<String> reasons = record.getReasonsEn() == null ? List.of()
                : JsonUtils.fromJson(record.getReasonsEn(), STRINGS);
        return new KycPlayerView(String.valueOf(record.getId()), record.getStatus(), reasons,
                record.getCreatedAt() == null ? null : record.getCreatedAt().atZone(BingoTime.ZONE).toInstant(),
                record.getCompletedAt() == null ? null : record.getCompletedAt().atZone(BingoTime.ZONE).toInstant());
    }
}
