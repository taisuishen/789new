package com.bingo789.kyc.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.obs.ObsStorage;
import com.bingo789.kyc.KycErrorCode;
import com.bingo789.kyc.api.dto.FaceCheckCommand;
import com.bingo789.kyc.api.dto.FaceCheckView;
import com.bingo789.kyc.config.KycConfigService;
import com.bingo789.kyc.domain.KycRecord;
import com.bingo789.kyc.mapper.KycRecordMapper;
import com.bingo789.kyc.runpod.RunPodClient;
import com.bingo789.kyc.runpod.RunPodJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Synchronous "is this the verified player?" check: a new photo against the selfie of the player's approved KYC.
 * First the facecmp resident pod (POST FaceCompareUrl {img1Url, img2Url}, FaceCompareTimeoutMs), then the serverless
 * KYC worker (action face_compare via /runsync) as fallback, as in facecmp/DEPLOY.md. Keep both thresholds equal
 * (FACECMP_THRESHOLD on the pod, KYC_FACE_COMPARE_THRESHOLD on the worker) so both paths agree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaceCheckService {

    private static final Duration URL_TTL = Duration.ofMinutes(10);

    private final KycRecordMapper mapper;
    private final ObsStorage storage;
    private final KycConfigService config;
    private final RunPodClient runPod;
    private final HttpClient httpClient;

    public FaceCheckView check(long userId, FaceCheckCommand command) {
        KycRecord approved = mapper.selectLatestApproved(userId);
        BizException.check(approved != null, KycErrorCode.NO_VERIFIED_SELFIE);
        String key = storage.keyOf(command.imageUrl());
        BizException.check(key != null && ObsStorage.isUnder(key, KycSubmissionService.folderOf(userId)),
                KycErrorCode.INVALID_IMAGE);
        String reference = storage.signedUrl(approved.getSelfieKey(), URL_TTL);
        String candidate = storage.signedUrl(key, URL_TTL);

        String podUrl = config.faceCompareUrl();
        if (podUrl != null) {
            try {
                return viaPod(podUrl, reference, candidate);
            } catch (RuntimeException e) {
                log.warn("facecmp pod failed for user {}, falling back to the serverless worker: {}", userId, e.toString());
            }
        }
        try {
            return viaServerless(userId, reference, candidate);
        } catch (RuntimeException e) {
            log.warn("serverless face compare failed for user {}: {}", userId, e.toString());
            return new FaceCheckView(false, false, null, null, "NONE", "face compare temporarily unavailable");
        }
    }

    /** facecmp answers {success, reason, match, distance, threshold, timeMs}, HTTP 200 unless it crashed. */
    private FaceCheckView viaPod(String url, String img1, String img2) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.faceCompareTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(Map.of("img1Url", img1, "img2Url", img2))))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("facecmp unreachable: " + e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("facecmp answered HTTP " + response.statusCode());
        }
        JsonNode body = JsonUtils.mapper().readTree(response.body());
        boolean success = bool(body, "success");
        return new FaceCheckView(success, success && bool(body, "match"), number(body, "distance"),
                number(body, "threshold"), "POD", success ? null : text(body, "reason"));
    }

    /** Worker output: {success, decision, reasons, result: {verified, distance, threshold, ...}}. */
    private FaceCheckView viaServerless(long userId, String img1, String img2) {
        RunPodJob job = runPod.runSync(Map.of("action", "face_compare", "userId", userId,
                "imageAUrl", img1, "imageBUrl", img2), config.faceCompareFallbackTimeout());
        if (!job.completed() || job.output() == null) {
            throw new IllegalStateException("RunPod face_compare job " + job.id() + " ended " + job.status());
        }
        JsonNode output = job.output();
        JsonNode result = output.get("result");
        boolean error = "error".equals(text(output, "decision"));
        JsonNode reasons = output.get("reasonsEn") != null ? output.get("reasonsEn") : output.get("reasons");
        String reason = error && reasons != null && reasons.isArray() && !reasons.isEmpty() ? text(reasons, 0) : null;
        return new FaceCheckView(!error, !error && bool(result, "verified"), number(result, "distance"),
                number(result, "threshold"), "SERVERLESS", error ? (reason != null ? reason : "face compare failed") : null);
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.isString() ? value.stringValue() : value.toString();
    }

    private static String text(JsonNode array, int index) {
        JsonNode value = array.get(index);
        return value == null || value.isNull() ? null : value.isString() ? value.stringValue() : value.toString();
    }
}
