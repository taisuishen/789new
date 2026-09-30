package com.bingo789.kyc.job;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.kyc.config.KycConfigService;
import com.bingo789.kyc.domain.KycRecord;
import com.bingo789.kyc.mapper.KycRecordMapper;
import com.bingo789.kyc.runpod.RunPodJob;
import com.bingo789.kyc.runpod.RunPodClient;
import com.bingo789.kyc.service.KycResultService;
import com.bingo789.kyc.service.KycSubmissionService;
import com.bingo789.kyc.service.UserKycSync;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * XXL-Job handlers (schedule each every 10 s, routing FIRST / single instance; the conditional status updates make
 * overlapping runs harmless anyway):
 * <ul>
 *   <li>kycSubmitRetryJob: status 0 rows due for resubmission;</li>
 *   <li>kycResultPollJob: status 1 rows without a webhook after KycPollAfterSeconds ask RunPod /status;</li>
 *   <li>kycTimeoutJob: rows still open KycTimeoutSeconds (5 min) after submission -> 4 RunPod 响应失败 (rejected),
 *       after one last /status look, and the RunPod job is cancelled;</li>
 *   <li>kycUserSyncJob: rows whose status user-service has not confirmed yet.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KycJobs {

    private static final int BATCH = 100;
    private static final int POLL_PAGE = 200;
    private static final int MAX_ROWS_PER_RUN = 2_000;
    /** Leaves the synchronous user-service call of a transition that is happening right now alone. */
    private static final Duration SYNC_GRACE = Duration.ofSeconds(10);

    private final KycRecordMapper mapper;
    private final KycSubmissionService submissionService;
    private final KycResultService resultService;
    private final RunPodClient runPod;
    private final UserKycSync userSync;
    private final KycConfigService config;

    @XxlJob("kycSubmitRetryJob")
    public void kycSubmitRetryJob() {
        // rows too close to the deadline for a useful job are left to kycTimeoutJob
        LocalDateTime createdAfter = BingoTime.now().minus(config.timeout()).plusSeconds(10);
        int done = run(() -> mapper.selectDueForSubmit(BingoTime.now(), createdAfter, BATCH), submissionService::submitToRunPod);
        XxlJobHelper.log("resubmitted={}", done);
    }

    @XxlJob("kycResultPollJob")
    public void kycResultPollJob() {
        // one page of the oldest waiting jobs per run: they stay at status 1 while RunPod is still working, so a
        // second page would return the same rows; at every-10-s scheduling that is ~1,200 polls a minute
        List<KycRecord> rows = mapper.selectProcessingSince(BingoTime.now().minus(config.pollAfter()), POLL_PAGE);
        int done = 0;
        for (KycRecord row : rows) {
            try {
                poll(row);
                done++;
            } catch (RuntimeException e) {
                log.warn("RunPod status poll failed for KYC submission {}: {}", row.getId(), e.toString());
            }
        }
        XxlJobHelper.log("polled={}", done);
    }

    @XxlJob("kycTimeoutJob")
    public void kycTimeoutJob() {
        LocalDateTime before = BingoTime.now().minus(config.timeout());
        int done = run(() -> mapper.selectExpired(before, BATCH), this::expire);
        if (done > 0) {
            log.warn("{} KYC submissions got no RunPod result within {}s", done, config.timeout().toSeconds());
        }
        XxlJobHelper.log("expired={}", done);
    }

    @XxlJob("kycUserSyncJob")
    public void kycUserSyncJob() {
        int done = run(() -> mapper.selectUserUnsynced(BingoTime.now().minus(SYNC_GRACE), BATCH), userSync::push);
        XxlJobHelper.log("synced={}", done);
    }

    private void poll(KycRecord record) {
        RunPodJob job = runPod.status(record.getRunpodJobId());
        if (job.completed() || job.failed()) {
            resultService.onJob(job);
        }
    }

    private void expire(KycRecord record) {
        if (record.getRunpodJobId() != null) {
            try {
                // the webhook may just have been lost: a finished job still counts
                RunPodJob job = runPod.status(record.getRunpodJobId());
                if (job.completed() && job.output() != null && resultService.applyOutput(record, job.output())) {
                    return;
                }
            } catch (RuntimeException e) {
                log.debug("last status look for KYC submission {} failed: {}", record.getId(), e.toString());
            }
        }
        String error = "no RunPod result within " + config.timeout().toSeconds() + "s ("
                + (record.getRunpodJobId() == null ? "never accepted, " + record.getSubmitAttempts() + " attempts"
                : "job " + record.getRunpodJobId()) + ")";
        if (mapper.fail(record.getId(), error, BingoTime.now()) == 1) {
            if (record.getRunpodJobId() != null) {
                submissionService.cancelQuietly(record.getRunpodJobId());
            }
            userSync.push(mapper.selectById(record.getId()));
        }
    }

    /**
     * Pages until a short page, a page that starts with the same row as the previous one (rows whose step failed and
     * stayed selectable are left for the next run), or the per-run cap. One row's failure never stops the others.
     */
    private int run(Supplier<List<KycRecord>> page, Consumer<KycRecord> action) {
        int done = 0;
        int seen = 0;
        Long previousFirst = null;
        while (seen < MAX_ROWS_PER_RUN) {
            List<KycRecord> rows = page.get();
            if (rows.isEmpty() || rows.getFirst().getId().equals(previousFirst)) {
                break;
            }
            previousFirst = rows.getFirst().getId();
            for (KycRecord row : rows) {
                seen++;
                try {
                    action.accept(row);
                    done++;
                } catch (RuntimeException e) {
                    log.warn("KYC job step failed for submission {}: {}", row.getId(), e.toString());
                }
            }
            if (rows.size() < BATCH) {
                break;
            }
        }
        return done;
    }
}
