package com.bingo789.kyc.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.common.obs.ObsStorage;
import com.bingo789.kyc.KycErrorCode;
import com.bingo789.kyc.config.KycConfigService;
import com.bingo789.kyc.domain.KycRecord;
import com.bingo789.kyc.domain.KycRecordStatus;
import com.bingo789.kyc.mapper.KycRecordMapper;
import com.bingo789.kyc.runpod.RunPodClient;
import com.bingo789.kyc.web.dto.SubmitKycRequest;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KYC submission: the player uploaded the images to OBS (their own folder, see {@link #folderOf}) and sends the
 * addresses back. The submission is stored and sent to RunPod right away:
 * <ul>
 *   <li>RunPod accepted it -> status 1 处理中 (the result arrives by webhook, or by the status poll);</li>
 *   <li>the call failed -> status 0 待提交, resubmitted by kycSubmitRetryJob with backoff;</li>
 *   <li>no result {@code KycTimeoutSeconds} (5 min) after the submission -> status 4 RunPod 响应失败 (rejected).</li>
 * </ul>
 * The row is written first (status 0) and moved to 1 by a conditional update after RunPod answered, so a crash in
 * between leaves a status-0 row for the retry job instead of a job nobody knows about.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycSubmissionService {

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    private static final Duration MIN_JOB_TTL = Duration.ofSeconds(10);

    private final KycRecordMapper mapper;
    private final RunPodClient runPod;
    private final ObsStorage storage;
    private final KycConfigService config;
    private final UserClient userClient;
    private final UserKycSync userSync;
    private final SnowflakeIdGenerator idGenerator;

    /** OBS folder of a player's KYC images; submissions only accept images from it. */
    public static String folderOf(long userId) {
        return "kyc/" + userId;
    }

    public KycRecord submit(long userId, SubmitKycRequest request) {
        PlayerStatusView player = userClient.playerStatus(userId);
        BizException.check(player.accountStatus() == AccountStatus.ACTIVE
                && !"SHADOW_ACCOUNT".equals(player.reason()), KycErrorCode.NOT_ALLOWED);
        BizException.check(player.kycStatus() != KycStatus.VERIFIED && mapper.countApproved(userId) == 0,
                KycErrorCode.ALREADY_VERIFIED);
        BizException.check(mapper.selectOpen(userId) == null, KycErrorCode.SUBMISSION_IN_PROGRESS);
        LocalDateTime startOfDay = LocalDate.now(BingoTime.ZONE).atStartOfDay();
        BizException.check(mapper.countSince(userId, startOfDay) < config.maxSubmissionsPerDay(),
                KycErrorCode.DAILY_LIMIT_REACHED);

        LocalDateTime now = BingoTime.now();
        KycRecord record = new KycRecord();
        record.setId(idGenerator.nextId());
        record.setUserId(userId);
        record.setUserLine(UserLine.orDefault(player.userLine()));
        record.setIdentityType(request.identityType());
        record.setIdFrontKey(ownImage(userId, request.idFrontUrl()));
        record.setIdBackKey(request.idBackUrl() == null || request.idBackUrl().isBlank() ? null : ownImage(userId, request.idBackUrl()));
        record.setSelfieKey(ownImage(userId, request.selfieUrl()));
        record.setStatus(KycRecordStatus.PENDING_SUBMIT.code());
        record.setSubmitAttempts(0);
        record.setNextSubmitAt(now);
        record.setUserSynced(0);
        record.setCreatedAt(now);
        try {
            mapper.insert(record);
        } catch (RuntimeException e) {
            if (DuplicateKeys.isDuplicateKey(e)) {
                // uk_open_user: a concurrent submission of the same player won
                throw new BizException(KycErrorCode.SUBMISSION_IN_PROGRESS);
            }
            throw e;
        }
        log.info("KYC submission {} created for user {} (identityType {})", record.getId(), userId, request.identityType());
        userSync.push(record);
        submitToRunPod(record);
        return mapper.selectById(record.getId());
    }

    /**
     * One /run attempt for a status-0 row (first attempt and retries). Failure keeps it at 0 with a backoff; after
     * KycMaxSubmitAttempts failures it is closed as 4 RunPod 响应失败.
     */
    public void submitToRunPod(KycRecord record) {
        LocalDateTime now = BingoTime.now();
        Duration left = Duration.between(now, record.getCreatedAt().plus(config.timeout()));
        if (left.compareTo(MIN_JOB_TTL) < 0) {
            return; // kycTimeoutJob closes it
        }
        String jobId;
        try {
            jobId = runPod.run(input(record), config.runPodWebhookUrl(), left);
        } catch (RuntimeException e) {
            int attempts = record.getSubmitAttempts() + 1;
            String error = truncate("RunPod submission failed (attempt " + attempts + "): " + e.getMessage());
            if (attempts >= config.maxSubmitAttempts()) {
                if (mapper.fail(record.getId(), error, now) == 1) {
                    log.warn("KYC submission {} given up after {} attempts: {}", record.getId(), attempts, e.getMessage());
                    userSync.push(mapper.selectById(record.getId()));
                }
            } else {
                mapper.markSubmitFailed(record.getId(), error, now.plus(backoff(attempts)));
                log.warn("KYC submission {} not accepted by RunPod (attempt {}), retrying: {}", record.getId(), attempts,
                        e.getMessage());
            }
            return;
        }
        if (mapper.markSubmitted(record.getId(), jobId, BingoTime.now()) == 1) {
            log.info("KYC submission {} queued at RunPod as job {}", record.getId(), jobId);
        } else {
            // closed meanwhile (timeout) or submitted by another run: the job is not needed
            log.info("KYC submission {} no longer waiting for submission, cancelling RunPod job {}", record.getId(), jobId);
            cancelQuietly(jobId);
        }
    }

    public void cancelQuietly(String jobId) {
        try {
            runPod.cancel(jobId);
        } catch (RuntimeException e) {
            log.debug("cancel of RunPod job {} failed: {}", jobId, e.toString());
        }
    }

    /** Input of the bbwave_face runpod_handler (action kyc); images as signed URLs valid for KycImageUrlTtlSeconds. */
    private Map<String, Object> input(KycRecord record) {
        Duration ttl = config.imageUrlTtl();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("action", "kyc");
        input.put("requestId", String.valueOf(record.getId()));
        input.put("userId", record.getUserId());
        input.put("identityType", record.getIdentityType());
        input.put("idFrontUrl", storage.signedUrl(record.getIdFrontKey(), ttl));
        input.put("idBackUrl", record.getIdBackKey() == null ? null : storage.signedUrl(record.getIdBackKey(), ttl));
        input.put("selfieUrl", storage.signedUrl(record.getSelfieKey(), ttl));
        input.put("enableOcr", config.enableOcr());
        return input;
    }

    /** The address must point into the player's own folder of our bucket, and the object must exist. */
    private String ownImage(long userId, String urlOrKey) {
        String key = storage.keyOf(urlOrKey);
        BizException.check(key != null && ObsStorage.isUnder(key, folderOf(userId)) && storage.exists(key),
                KycErrorCode.INVALID_IMAGE);
        return key;
    }

    private Duration backoff(int attempts) {
        Duration delay = config.retryBaseDelay().multipliedBy(1L << Math.min(attempts - 1, 10));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static String truncate(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
