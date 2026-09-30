package com.bingo789.betrecord.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bingo789.betrecord.BetRecordErrorCode;
import com.bingo789.betrecord.entity.GameRound;
import com.bingo789.betrecord.mapper.GameRoundMapper;
import com.bingo789.betrecord.web.dto.PageView;
import com.bingo789.betrecord.web.dto.RoundView;
import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.Result;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.common.web.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Player round history. Served from read replicas via idx_user_date, pruned to the partitions of the range. */
@RestController
@RequestMapping("/api/bet-records")
@RequiredArgsConstructor
public class BetRecordController {

    private static final int MAX_RANGE_DAYS = 31;
    private static final int MAX_PAGE_SIZE = 100;

    private final GameRoundMapper roundMapper;
    private final ShardTemplate shards;

    /** Dates are UTC+8 round dates, both inclusive; defaults to the last 7 days. */
    @GetMapping("/rounds")
    public Result<PageView<RoundView>> rounds(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        long userId = CurrentUser.requireUserId();
        LocalDate end = to != null ? to : LocalDate.now(BingoTime.ZONE);
        LocalDate start = from != null ? from : end.minusDays(6);
        BizException.check(!end.isBefore(start), CommonErrorCode.BAD_REQUEST, "to must not be before from");
        BizException.check(ChronoUnit.DAYS.between(start, end) < MAX_RANGE_DAYS, BetRecordErrorCode.DATE_RANGE_TOO_LARGE);

        IPage<GameRound> result = shards.forUser(userId, () -> roundMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.clamp(size, 1, MAX_PAGE_SIZE)),
                Wrappers.<GameRound>lambdaQuery()
                        .eq(GameRound::getUserId, userId)
                        .between(GameRound::getRoundDate, start, end)
                        .orderByDesc(GameRound::getRoundDate)
                        .orderByDesc(GameRound::getId)));
        return Result.ok(new PageView<>(result.getRecords().stream().map(RoundView::of).toList(),
                result.getTotal(), result.getCurrent(), result.getSize()));
    }
}
