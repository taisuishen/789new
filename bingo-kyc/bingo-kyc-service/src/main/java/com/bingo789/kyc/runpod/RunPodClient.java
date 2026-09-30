package com.bingo789.kyc.runpod;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.kyc.config.KycConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RunPod serverless API (https://api.runpod.ai/v2/{endpointId}/...): {@code /run} (async, result via webhook),
 * {@code /status/{id}}, {@code /cancel/{id}}, {@code /runsync}. Endpoint, key and timeouts come from the config table.
 */
@Component
@RequiredArgsConstructor
public class RunPodClient {

    private final HttpClient httpClient;
    private final KycConfigService config;

    /**
     * Queues a job. {@code ttl} bounds the job's life at RunPod (queue + execution), so work we would reject as timed
     * out anyway is dropped there too.
     *
     * @return RunPod job id
     */
    public String run(Map<String, Object> input, String webhookUrl, Duration ttl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", input);
        if (webhookUrl != null) {
            body.put("webhook", webhookUrl);
        }
        // verify: policy.ttl / executionTimeout are milliseconds in the RunPod job policy
        body.put("policy", Map.of("ttl", ttl.toMillis(), "executionTimeout", ttl.toMillis()));
        JsonNode response = post("/run", body, config.runPodHttpTimeout());
        String id = text(response, "id");
        if (id == null) {
            throw new RunPodException("RunPod /run answered without a job id: " + abbreviate(response.toString()));
        }
        return id;
    }

    public RunPodJob status(String jobId) {
        return RunPodJob.of(send(HttpRequest.newBuilder(endpoint("/status/" + jobId)).GET(), config.runPodHttpTimeout()));
    }

    public void cancel(String jobId) {
        send(HttpRequest.newBuilder(endpoint("/cancel/" + jobId)).POST(HttpRequest.BodyPublishers.noBody()),
                config.runPodHttpTimeout());
    }

    /** Synchronous job (face compare fallback); RunPod answers IN_PROGRESS when it outlives its own wait. */
    public RunPodJob runSync(Map<String, Object> input, Duration timeout) {
        return RunPodJob.of(post("/runsync", Map.of("input", input), timeout));
    }

    private JsonNode post(String path, Object body, Duration timeout) {
        return send(HttpRequest.newBuilder(endpoint(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body))), timeout);
    }

    private JsonNode send(HttpRequest.Builder builder, Duration timeout) {
        HttpRequest request = builder
                .header("Authorization", "Bearer " + config.runPodApiKey())
                .header("Accept", "application/json")
                .timeout(timeout)
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RunPodException("RunPod " + request.uri().getPath() + " unreachable: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunPodException("interrupted while calling RunPod");
        }
        if (response.statusCode() / 100 != 2) {
            throw new RunPodException("RunPod " + request.uri().getPath() + " answered HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        try {
            return JsonUtils.mapper().readTree(response.body());
        } catch (RuntimeException e) {
            throw new RunPodException("RunPod answered invalid JSON: " + abbreviate(response.body()));
        }
    }

    private URI endpoint(String path) {
        String base = config.runPodApiBaseUrl();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/" + config.runPodEndpointId() + path);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isString() ? value.stringValue() : value.toString();
    }

    static String abbreviate(String value) {
        return value == null ? "" : value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
