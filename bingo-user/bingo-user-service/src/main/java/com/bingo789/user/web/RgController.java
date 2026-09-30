package com.bingo789.user.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.user.service.RgService;
import com.bingo789.user.web.dto.CoolOffRequest;
import com.bingo789.user.web.dto.RgSettingsResponse;
import com.bingo789.user.web.dto.SelfExclusionRequest;
import com.bingo789.user.web.dto.UpdateRgLimitsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/rg")
@RequiredArgsConstructor
public class RgController {

    private final RgService rgService;

    @GetMapping("/limits")
    public Result<RgSettingsResponse> limits() {
        return Result.ok(rgService.settings(CurrentUser.requireUserId()));
    }

    @PutMapping("/limits")
    public Result<RgSettingsResponse> updateLimits(@Valid @RequestBody UpdateRgLimitsRequest request) {
        return Result.ok(rgService.updateLimits(CurrentUser.requireUserId(), request));
    }

    /** Revokes every session of the player, including the one making this call. */
    @PostMapping("/self-exclusion")
    public Result<RgSettingsResponse> selfExclude(@Valid @RequestBody SelfExclusionRequest request) {
        return Result.ok(rgService.selfExclude(CurrentUser.requireUserId(), request));
    }

    /** Revokes every session of the player, including the one making this call. */
    @PostMapping("/cool-off")
    public Result<RgSettingsResponse> coolOff(@Valid @RequestBody CoolOffRequest request) {
        return Result.ok(rgService.coolOff(CurrentUser.requireUserId(), request));
    }
}
