package com.bingo789.kyc.runpod;

/** RunPod could not be reached or answered with an error; the caller retries. */
public class RunPodException extends RuntimeException {

    public RunPodException(String message) {
        super(message);
    }
}
