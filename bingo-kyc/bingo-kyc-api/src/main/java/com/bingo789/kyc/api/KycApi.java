package com.bingo789.kyc.api;

import com.bingo789.kyc.api.dto.FaceCheckCommand;
import com.bingo789.kyc.api.dto.FaceCheckView;
import com.bingo789.kyc.api.dto.KycSubmissionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** Internal KYC API (back office, other services). Never exposed by the gateway. */
public interface KycApi {

    String PREFIX = "/internal/kyc";

    /** The player's submissions, newest first, restricted to the viewer's lines ("1,2" or "*"). */
    @GetMapping(PREFIX + "/players/{userId}/submissions")
    List<KycSubmissionView> submissions(@PathVariable("userId") long userId, @RequestParam("lines") String lines);

    /**
     * Compares a new photo of the player (e.g. a selfie before a withdrawal) with the selfie of their approved KYC,
     * synchronously: facecmp pod first, the serverless worker as fallback.
     */
    @PostMapping(PREFIX + "/players/{userId}/face-check")
    FaceCheckView faceCheck(@PathVariable("userId") long userId, @RequestBody FaceCheckCommand command);
}
