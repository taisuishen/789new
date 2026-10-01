package com.bingo789.promotion.job;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.mapper.PromotionRoundAppliedMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Suggested schedule: daily off-peak (e.g. 04:20). Keeps promotion_round_applied at {@link #KEEP}: rounds are
 * revised at most bet-record's late-event lookback (30 days, the wallet's idempotency window) after they started,
 * so older dedupe rows can never be needed again. Small batches, no long locks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoundAppliedRetentionJob {

    static final Duration KEEP = Duration.ofDays(35);
    private static final int BATCH = 5_000;

    private final PromotionRoundAppliedMapper mapper;

    @XxlJob("promotionRoundAppliedRetentionJob")
    public void purge() {
        LocalDateTime before = BingoTime.now().minus(KEEP);
        long total = 0;
        int deleted;
        do {
            deleted = mapper.deleteOlderThan(before, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        log.info("promotion_round_applied: {} rows older than {} deleted", total, before);
        XxlJobHelper.log("deleted={}", total);
    }
}
