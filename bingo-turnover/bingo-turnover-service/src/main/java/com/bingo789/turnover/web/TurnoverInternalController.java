package com.bingo789.turnover.web;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.line.LineScope;
import com.bingo789.turnover.api.TurnoverApi;
import com.bingo789.turnover.api.dto.BucketView;
import com.bingo789.turnover.api.dto.ClearBucketsCommand;
import com.bingo789.turnover.api.dto.ManualBucketCommand;
import com.bingo789.turnover.api.dto.OutstandingView;
import com.bingo789.turnover.api.dto.TurnoverRecordView;
import com.bingo789.turnover.api.dto.TurnoverSettingView;
import com.bingo789.turnover.api.dto.UpdateTurnoverSettingCommand;
import com.bingo789.turnover.service.BucketService;
import com.bingo789.turnover.service.SettingService;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.UserLineView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Internal API: risk (withdrawal rule) and the future back office. Never exposed by the gateway. */
@RestController
@RequiredArgsConstructor
public class TurnoverInternalController implements TurnoverApi {

    private final BucketService bucketService;
    private final SettingService settingService;
    private final UserClient userClient;

    @Override
    public OutstandingView outstanding(@PathVariable("userId") long userId, @RequestParam("currency") String currency) {
        return bucketService.outstanding(userId, currency);
    }

    @Override
    public List<BucketView> buckets(@PathVariable("userId") long userId,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam("lines") String lines) {
        return bucketService.list(userId, status, LineScope.parse(lines));
    }

    @Override
    public List<TurnoverRecordView> records(@PathVariable("userId") long userId,
                                            @RequestParam(value = "bucketId", required = false) Long bucketId,
                                            @RequestParam("lines") String lines,
                                            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return bucketService.records(userId, bucketId, LineScope.parse(lines), limit);
    }

    @Override
    public BucketView addBucket(@PathVariable("userId") long userId, @Valid @RequestBody ManualBucketCommand command) {
        // the bucket carries the player's current line, like every row written for the player
        List<UserLineView> lines = userClient.userLines(List.of(userId));
        BizException.check(!lines.isEmpty(), CommonErrorCode.NOT_FOUND, "player " + userId + " not found");
        return bucketService.addManual(userId, lines.getFirst().userLine(), command);
    }

    @Override
    public int clearBuckets(@PathVariable("userId") long userId, @Valid @RequestBody ClearBucketsCommand command) {
        return bucketService.clear(userId, command);
    }

    @Override
    public List<TurnoverSettingView> settings(@RequestParam("lines") String lines) {
        return settingService.list(LineScope.parse(lines));
    }

    @Override
    public TurnoverSettingView updateSetting(@Valid @RequestBody UpdateTurnoverSettingCommand command) {
        return settingService.update(command);
    }
}
