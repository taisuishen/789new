package com.bingo789.kyc.web;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.Result;
import com.bingo789.common.obs.ObsStorage;
import com.bingo789.common.obs.StoredObject;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.kyc.domain.KycRecord;
import com.bingo789.kyc.mapper.KycRecordMapper;
import com.bingo789.kyc.service.KycSubmissionService;
import com.bingo789.kyc.web.dto.KycPlayerView;
import com.bingo789.kyc.web.dto.SubmitKycRequest;
import com.bingo789.kyc.web.dto.UploadedImageView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Player KYC flow: 1) upload the ID front (back optional) and a selfie, one call per image; 2) submit the returned
 * addresses; 3) poll the latest submission until its status is 2 成功, 3 拒绝 or 4 (failed, submit again).
 */
@RestController
@RequestMapping("/api/kyc")
@RequiredArgsConstructor
public class KycPlayerController {

    private final ObsStorage storage;
    private final KycSubmissionService submissionService;
    private final KycRecordMapper mapper;

    /** JPEG, PNG or WebP up to 10 MB, stored in the player's private KYC folder. */
    @PostMapping(path = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UploadedImageView> upload(@RequestParam("file") MultipartFile file) {
        long userId = CurrentUser.requireUserId();
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "upload could not be read");
        }
        StoredObject stored = storage.uploadImage(KycSubmissionService.folderOf(userId), data);
        return Result.ok(new UploadedImageView(stored.url(), stored.key()));
    }

    @PostMapping("/submissions")
    public Result<KycPlayerView> submit(@Valid @RequestBody SubmitKycRequest request) {
        long userId = CurrentUser.requireUserId();
        return Result.ok(KycPlayerView.of(submissionService.submit(userId, request)));
    }

    /** Null data when the player never submitted. */
    @GetMapping("/submissions/latest")
    public Result<KycPlayerView> latest() {
        long userId = CurrentUser.requireUserId();
        KycRecord record = mapper.selectLatest(userId);
        return Result.ok(record == null ? null : KycPlayerView.of(record));
    }
}
