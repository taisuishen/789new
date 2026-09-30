package com.bingo789.kyc.web;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.kyc.config.KycConfigService;
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

/**
 * RunPod posts the finished job ({id, status, output, ...}) here. Reached only through the callback ingress (the
 * player gateway blocks /callback/**). RunPod does not sign webhooks, so the URL carries a shared token
 * (config RunPodWebhookToken) compared in constant time; the status poll covers lost or rejected deliveries.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RunPodWebhookController {

    private final KycConfigService config;
    private final KycResultService resultService;

    @PostMapping("/callback/runpod/kyc")
    public ResponseEntity<Void> kycResult(@RequestParam(value = "token", required = false) String token,
                                          @RequestBody String body) {
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                config.runPodWebhookToken().getBytes(StandardCharsets.UTF_8))) {
            log.warn("RunPod webhook with a wrong token rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        RunPodJob job;
        try {
            job = RunPodJob.of(JsonUtils.mapper().readTree(body));
        } catch (RuntimeException e) {
            log.warn("malformed RunPod webhook body ignored: {}", e.toString());
            return ResponseEntity.badRequest().build();
        }
        resultService.onJob(job);
        return ResponseEntity.ok().build();
    }
}
