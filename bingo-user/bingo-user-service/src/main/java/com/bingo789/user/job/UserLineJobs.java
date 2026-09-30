package com.bingo789.user.job;

import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.user.mapper.UserLineMigrationMapper;
import com.bingo789.user.service.UserLineService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Re-sends line migrations the wallet has not acknowledged (the call right after the migration failed). Until then
 * new ledger rows of that player still carry the old line, so run it often (e.g. every 30 s).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserLineJobs {

    private static final int BATCH_SIZE = 200;
    private static final int MAX_USERS_PER_RUN = 5_000;
    /** Leaves the synchronous call of a migration that is committing right now alone. */
    private static final Duration GRACE = Duration.ofSeconds(10);

    private final UserLineMigrationMapper migrationMapper;
    private final UserLineService userLineService;
    private final Clock clock;

    @XxlJob("userLineWalletSyncJob")
    public void userLineWalletSyncJob() {
        Instant before = clock.instant().minus(GRACE);
        long cursor = 0L;
        int synced = 0;
        int failed = 0;
        int scanned = 0;
        while (scanned < MAX_USERS_PER_RUN) {
            long after = cursor;
            List<Long> users = MasterRoute.run(() -> migrationMapper.selectUnsyncedUsers(before, after, BATCH_SIZE));
            for (long userId : users) {
                cursor = userId;
                scanned++;
                if (userLineService.syncWallet(userId)) {
                    synced++;
                } else {
                    failed++;
                }
            }
            if (users.size() < BATCH_SIZE) {
                break;
            }
        }
        if (failed > 0) {
            log.warn("userLineWalletSyncJob: {} synced, {} still failing", synced, failed);
        }
        XxlJobHelper.log("synced={}, failed={}", synced, failed);
    }
}
