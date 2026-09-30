package com.bingo789.promotion.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.promotion.service.ActivityService;
import com.bingo789.promotion.web.dto.ActivityView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/promotion/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    /** Live promotions of the calling player's line. */
    @GetMapping
    public Result<List<ActivityView>> list() {
        return Result.ok(activityService.listFor(CurrentUser.requireUserId()));
    }
}
