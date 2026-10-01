package com.bingo789.game.wallet;

import com.bingo789.common.core.time.BingoTime;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Deletes CLOSED open_bet rows {@link #KEEP} after they closed. The window covers the provider's late retries of a
 * payout or refund whose token has expired by then (the row is what still names the player). PENDING and OPEN rows
 * are never deleted: they are the stakes the provider still has to settle. Schedule it hourly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenBetRetentionJob {

    static final Duration KEEP = Duration.ofDays(7);
    private static final int BATCH = 2000;
    private static final int MAX_BATCHES = 500;

    private final OpenBetMapper mapper;

    @XxlJob("openBetRetentionJob")
    public void run() {
        LocalDateTime before = LocalDateTime.now(BingoTime.ZONE).minus(KEEP);
        long total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int rows = mapper.deleteClosedBefore(before, BATCH);
            total += rows;
            if (rows < BATCH) {
                break;
            }
        }
        log.info("open_bet retention: {} closed rows older than {} deleted", total, KEEP);
        XxlJobHelper.log("deleted={}", total);
    }
}
