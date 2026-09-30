package com.bingo789.user.job;

import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.user.entity.UserRgSetting;
import com.bingo789.user.mapper.UserRgSettingMapper;
import com.bingo789.user.service.RgService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Keeps the wallet BET_LOCKED state in line with RG restrictions. Both jobs are idempotent and safe to run on
 * several executors at once (every state change is a conditional update). Job param: optional batch size.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RgWalletJobs {

    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final int MAX_BATCH_SIZE = 1000;
    /** Bounds one execution; the next run picks up the rest. */
    private static final int MAX_ROWS_PER_RUN = 20_000;

    private final UserRgSettingMapper rgMapper;
    private final RgService rgService;
    private final Clock clock;

    /** Sets wallets back to ACTIVE once self-exclusion and cool-off have both expired. */
    @XxlJob("rgExpiryJob")
    public void rgExpiryJob() {
        int batchSize = batchSize();
        run("rgExpiryJob", (after, now) -> rgMapper.selectExpiredLocks(now, after, batchSize), batchSize,
                rgService::releaseExpiredLock);
    }

    /** Re-sends BET_LOCKED where the wallet call failed when the restriction was set. */
    @XxlJob("rgWalletLockRetryJob")
    public void rgWalletLockRetryJob() {
        int batchSize = batchSize();
        run("rgWalletLockRetryJob", (after, now) -> rgMapper.selectPendingLocks(after, batchSize), batchSize,
                rgService::retryWalletLock);
    }

    private void run(String name, BiFunction<Long, Instant, List<UserRgSetting>> page, int batchSize,
                     BiFunction<UserRgSetting, Instant, RgService.JobOutcome> action) {
        int done = 0;
        int skipped = 0;
        int failed = 0;
        int scanned = 0;
        long cursor = 0L;
        while (scanned < MAX_ROWS_PER_RUN) {
            Instant now = clock.instant();
            long after = cursor;
            List<UserRgSetting> rows = MasterRoute.run(() -> page.apply(after, now));
            for (UserRgSetting row : rows) {
                cursor = row.getUserId();
                scanned++;
                RgService.JobOutcome outcome;
                try {
                    outcome = action.apply(row, now);
                } catch (RuntimeException e) {
                    log.warn("{} failed for user {}", name, row.getUserId(), e);
                    outcome = RgService.JobOutcome.FAILED;
                }
                switch (outcome) {
                    case DONE -> done++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                }
            }
            if (rows.size() < batchSize) {
                break;
            }
        }
        XxlJobHelper.log("{} scanned={}, done={}, skipped={}, failed={}", name, scanned, done, skipped, failed);
        if (failed > 0) {
            XxlJobHelper.handleFail(name + ": " + failed + " players failed, see service log");
        }
    }

    private static int batchSize() {
        String param = XxlJobHelper.getJobParam();
        if (param == null || param.isBlank()) {
            return DEFAULT_BATCH_SIZE;
        }
        try {
            return Math.clamp(Integer.parseInt(param.trim()), 1, MAX_BATCH_SIZE);
        } catch (NumberFormatException e) {
            return DEFAULT_BATCH_SIZE;
        }
    }
}
