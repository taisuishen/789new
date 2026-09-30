package com.bingo789.turnover.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.core.line.LineScope;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.turnover.api.dto.BucketView;
import com.bingo789.turnover.service.BucketService;
import com.bingo789.turnover.web.dto.PlayerBucketView;
import com.bingo789.turnover.web.dto.PlayerRecordView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The player's own open wagering requirements ("流水进度"). */
@RestController
@RequestMapping("/api/turnover")
@RequiredArgsConstructor
public class PlayerTurnoverController {

    private final BucketService bucketService;

    @GetMapping("/buckets")
    public Result<List<PlayerBucketView>> buckets() {
        long userId = CurrentUser.requireUserId();
        List<BucketView> active = bucketService.active(userId);
        return Result.ok(active.stream().map(PlayerBucketView::of).toList());
    }

    /** The player's own 稽核记录 (latest 100, optionally of one bucket). */
    @GetMapping("/records")
    public Result<List<PlayerRecordView>> records(@RequestParam(value = "bucketId", required = false) Long bucketId) {
        long userId = CurrentUser.requireUserId();
        return Result.ok(bucketService.records(userId, bucketId, LineScope.all(), 100).stream()
                .map(PlayerRecordView::of).toList());
    }
}
