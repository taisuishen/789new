package com.bingo789.turnover.api;

import com.bingo789.turnover.api.dto.BucketView;
import com.bingo789.turnover.api.dto.ClearBucketsCommand;
import com.bingo789.turnover.api.dto.ManualBucketCommand;
import com.bingo789.turnover.api.dto.OutstandingView;
import com.bingo789.turnover.api.dto.TurnoverRecordView;
import com.bingo789.turnover.api.dto.TurnoverSettingView;
import com.bingo789.turnover.api.dto.UpdateTurnoverSettingCommand;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Wagering requirements ("稽核"). Query parameters named {@code lines} use the LineScope wire format ("1,2", or "*"
 * for every line) and restrict the answer to buckets written on those lines.
 */
public interface TurnoverApi {

    String PREFIX = "/internal/turnover";

    /** Sum over the player's ACTIVE buckets in {@code currency}; the withdrawal rule of risk reads this. */
    @GetMapping(PREFIX + "/players/{userId}/outstanding")
    OutstandingView outstanding(@PathVariable("userId") long userId, @RequestParam("currency") String currency);

    /** Back office: the player's buckets, newest first; {@code status} optional (ACTIVE, COMPLETED, CLEARED, VOID). */
    @GetMapping(PREFIX + "/players/{userId}/buckets")
    List<BucketView> buckets(@PathVariable("userId") long userId,
                             @RequestParam(value = "status", required = false) String status,
                             @RequestParam("lines") String lines);

    /** Back office: 稽核记录 (creation, every round's contribution, clears), newest first; {@code bucketId} optional. */
    @GetMapping(PREFIX + "/players/{userId}/records")
    List<TurnoverRecordView> records(@PathVariable("userId") long userId,
                                     @RequestParam(value = "bucketId", required = false) Long bucketId,
                                     @RequestParam("lines") String lines,
                                     @RequestParam(value = "limit", defaultValue = "100") int limit);

    /** Back office: adds a bucket by hand (idempotent per {@code ticketNo}). */
    @PostMapping(PREFIX + "/players/{userId}/buckets")
    BucketView addBucket(@PathVariable("userId") long userId, @RequestBody ManualBucketCommand command);

    /** Back office: clears ACTIVE buckets (one, or all of a currency); returns how many were cleared. */
    @PostMapping(PREFIX + "/players/{userId}/buckets/clear")
    int clearBuckets(@PathVariable("userId") long userId, @RequestBody ClearBucketsCommand command);

    @GetMapping(PREFIX + "/settings")
    List<TurnoverSettingView> settings(@RequestParam("lines") String lines);

    /** Back office: auto-clear thresholds and the deposit multiplier of one line and currency. */
    @PutMapping(PREFIX + "/settings")
    TurnoverSettingView updateSetting(@RequestBody UpdateTurnoverSettingCommand command);
}
