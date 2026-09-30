package com.bingo789.kyc.service;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.kyc.config.KycConfigService;
import com.bingo789.kyc.domain.KycRecord;
import com.bingo789.kyc.domain.KycRecordStatus;
import com.bingo789.kyc.mapper.KycRecordMapper;
import com.bingo789.kyc.runpod.RunPodJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Applies what RunPod reports about a job, from the webhook or from a /status poll (whichever comes first wins; the
 * other one finds the record closed). Worker output (bbwave_face runpod_handler): decision approved -> 2 成功,
 * rejected -> 3 拒绝, error (image download failed, processing error) -> 4 RunPod 响应失败. A job RunPod itself gave up
 * on (FAILED / CANCELLED / TIMED_OUT) is resubmitted while the submission is younger than the timeout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycResultService {

    private final KycRecordMapper mapper;
    private final KycConfigService config;
    private final UserKycSync userSync;

    public void onJob(RunPodJob job) {
        KycRecord record = find(job);
        if (record == null) {
            log.warn("RunPod job {} ({}) matches no KYC submission, ignored", job.id(), job.status());
            return;
        }
        if (job.completed() && job.output() != null) {
            applyOutput(record, job.output());
        } else if (job.failed()) {
            onRunPodGaveUp(record, job);
        }
    }

    /** @return true when this call closed the submission */
    public boolean applyOutput(KycRecord record, JsonNode output) {
        String decision = text(output, "decision");
        KycRecordStatus status = switch (decision == null ? "" : decision) {
            case "approved" -> KycRecordStatus.APPROVED;
            case "rejected" -> KycRecordStatus.REJECTED;
            default -> KycRecordStatus.RUNPOD_FAILED;
        };
        JsonNode result = output.get("result");
        JsonNode faceMatch = result == null || result.isNull() ? null : result.get("face_match");
        int rows = mapper.complete(record.getId(), status.code(), decision == null ? "error" : decision,
                json(output.get("reasons")), json(output.get("reasonsEn")), gender(text(output, "gender")),
                number(faceMatch, "distance"), number(faceMatch, "threshold"),
                result == null || result.isNull() ? null : result.toString(), BingoTime.now());
        if (rows == 0) {
            log.info("KYC submission {} already closed, late RunPod result ({}) ignored", record.getId(), decision);
            return false;
        }
        log.info("KYC submission {} of user {}: {} ({})", record.getId(), record.getUserId(), status, decision);
        userSync.push(mapper.selectById(record.getId()));
        return true;
    }

    private void onRunPodGaveUp(KycRecord record, RunPodJob job) {
        String error = "RunPod job " + job.id() + " " + job.status() + (job.error() == null ? "" : ": " + job.error());
        LocalDateTime now = BingoTime.now();
        if (record.getCreatedAt().plus(config.timeout()).isAfter(now)) {
            if (mapper.backToSubmit(record.getId(), job.id(), truncate(error), now) == 1) {
                log.warn("KYC submission {}: {}, resubmitting", record.getId(), error);
            }
        } else if (mapper.fail(record.getId(), truncate(error), now) == 1) {
            log.warn("KYC submission {} failed: {}", record.getId(), error);
            userSync.push(mapper.selectById(record.getId()));
        }
    }

    /** requestId (= record id) first: after a resubmission the result may come from an earlier job. */
    private KycRecord find(RunPodJob job) {
        String requestId = job.output() == null ? null : text(job.output(), "requestId");
        if (requestId != null && requestId.chars().allMatch(Character::isDigit) && !requestId.isEmpty()) {
            KycRecord record = mapper.selectById(Long.parseLong(requestId));
            if (record != null) {
                return record;
            }
        }
        return job.id() == null ? null : mapper.selectByJobId(job.id());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isString() ? value.stringValue() : value.toString();
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private static String json(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private static String gender(String code) {
        return code != null && code.length() == 1 && "mfu".contains(code) ? code : null;
    }

    private static String truncate(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
