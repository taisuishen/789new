package com.bingo789.kyc.runpod;

import tools.jackson.databind.JsonNode;

/**
 * A job as RunPod reports it (the /status answer, and the body RunPod posts to the webhook).
 *
 * @param status IN_QUEUE, IN_PROGRESS, COMPLETED, FAILED, CANCELLED or TIMED_OUT
 * @param output the handler's return value (bbwave_face: requestId, success, decision, reasons, reasonsEn, gender,
 *               result); null until COMPLETED
 */
public record RunPodJob(String id, String status, JsonNode output, String error) {

    public static RunPodJob of(JsonNode node) {
        JsonNode output = node.get("output");
        return new RunPodJob(RunPodClient.text(node, "id"), RunPodClient.text(node, "status"),
                output == null || output.isNull() ? null : output, RunPodClient.text(node, "error"));
    }

    public boolean completed() {
        return "COMPLETED".equals(status);
    }

    /** RunPod gave up on the job; our record may be resubmitted. */
    public boolean failed() {
        return "FAILED".equals(status) || "CANCELLED".equals(status) || "TIMED_OUT".equals(status);
    }
}
