package com.bingo789.kyc.web;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.kyc.config.KycConfigService;
import com.bingo789.kyc.runpod.RunPodClient;
import com.bingo789.kyc.runpod.RunPodJob;
import com.bingo789.kyc.service.KycResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * RunPod posts the finished job ({id, status, output, ...}) here. Reached only through the callback ingress (the
 * player gateway blocks /callback/**). RunPod does not sign webhooks, so the URL carries a shared token
 * (config RunPodWebhookToken) compared in constant time.
 * <p>
 * The webhook is only a TRIGGER: its body is never trusted as a KYC result (a leaked token would otherwise let anyone
 * approve any submission). Only the job id is taken from it; the result is fetched from RunPod's /status with our API
 * key, exactly as the poll does. The status poll also covers lost, rejected or failed deliveries.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RunPodWebhookController {

    /** RunPod job ids, e.g. "sync-0d7c..." / uuid-like; also keeps the id safe in the /status/{id} path. */
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");

    private final KycConfigService config;
    private final KycResultService resultService;
    private final RunPodClient runPod;

    @PostMapping("/callback/runpod/kyc")
    public ResponseEntity<Void> kycResult(@RequestParam(value = "token", required = false) String token,
                                          @RequestBody String body) {
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                config.runPodWebhookToken().getBytes(StandardCharsets.UTF_8))) {
            log.warn("RunPod webhook with a wrong token rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String jobId;
        try {
            jobId = RunPodJob.of(JsonUtils.mapper().readTree(body)).id();
        } catch (RuntimeException e) {
            log.warn("malformed RunPod webhook body ignored: {}", e.toString());
            return ResponseEntity.badRequest().build();
        }
        if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            RunPodJob job = runPod.status(jobId);
            if (job.completed() || job.failed()) {
                resultService.onJob(job);
            }
        } catch (RuntimeException e) {
            // 200 anyway: kycResultPollJob picks the submission up
            log.warn("RunPod status fetch for webhook job {} failed, left to the poll: {}", jobId, e.toString());
        }
        return ResponseEntity.ok().build();
    }
}
