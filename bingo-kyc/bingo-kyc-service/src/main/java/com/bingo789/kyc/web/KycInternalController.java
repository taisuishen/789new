package com.bingo789.kyc.web;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.line.LineScope;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.obs.ObsStorage;
import com.bingo789.kyc.api.KycApi;
import com.bingo789.kyc.api.dto.FaceCheckCommand;
import com.bingo789.kyc.api.dto.FaceCheckView;
import com.bingo789.kyc.api.dto.KycSubmissionView;
import com.bingo789.kyc.domain.KycRecord;
import com.bingo789.kyc.mapper.KycRecordMapper;
import com.bingo789.kyc.service.FaceCheckService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** Internal API: back office (review of submissions) and services that need a face check. */
@RestController
@RequiredArgsConstructor
public class KycInternalController implements KycApi {

    /** Back-office image links expire quickly: identity documents are sensitive personal data. */
    private static final Duration IMAGE_URL_TTL = Duration.ofMinutes(5);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final KycRecordMapper mapper;
    private final ObsStorage storage;
    private final FaceCheckService faceCheckService;

    @Override
    public List<KycSubmissionView> submissions(@PathVariable("userId") long userId, @RequestParam("lines") String lines) {
        LineScope scope = LineScope.parse(lines);
        return mapper.selectByUser(userId, scope.isAll() ? null : scope.lines()).stream().map(this::view).toList();
    }

    @Override
    public FaceCheckView faceCheck(@PathVariable("userId") long userId, @Valid @RequestBody FaceCheckCommand command) {
        return faceCheckService.check(userId, command);
    }

    private KycSubmissionView view(KycRecord r) {
        return new KycSubmissionView(r.getId(), r.getUserId(), r.getUserLine(), r.getIdentityType(), r.getStatus(),
                r.getDecision(), strings(r.getReasons()), strings(r.getReasonsEn()), r.getGender(), r.getFaceDistance(),
                r.getFaceThreshold(), r.getSubmitAttempts(), r.getLastError(),
                storage.signedUrl(r.getIdFrontKey(), IMAGE_URL_TTL),
                r.getIdBackKey() == null ? null : storage.signedUrl(r.getIdBackKey(), IMAGE_URL_TTL),
                storage.signedUrl(r.getSelfieKey(), IMAGE_URL_TTL), instant(r.getCreatedAt()), instant(r.getCompletedAt()));
    }

    private static List<String> strings(String json) {
        return json == null ? List.of() : JsonUtils.fromJson(json, STRINGS);
    }

    private static Instant instant(LocalDateTime local) {
        return local == null ? null : local.atZone(BingoTime.ZONE).toInstant();
    }
}
