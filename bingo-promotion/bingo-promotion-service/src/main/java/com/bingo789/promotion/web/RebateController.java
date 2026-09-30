package com.bingo789.promotion.web;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.promotion.service.RebateService;
import com.bingo789.promotion.web.dto.RebateRecordView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/promotion/rebates")
@RequiredArgsConstructor
public class RebateController {

    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 93;

    private final RebateService rebateService;
    private final PromotionProperties properties;

    /** @param from business day yyyy-MM-dd, default {@code to} minus 30 days; {@code to} defaults to today */
    @GetMapping
    public Result<List<RebateRecordView>> list(@RequestParam(value = "from", required = false) String from,
                                               @RequestParam(value = "to", required = false) String to) {
        long userId = CurrentUser.requireUserId();
        LocalDate end = to == null || to.isBlank() ? LocalDate.now(properties.zone()) : parseDate(to);
        LocalDate start = from == null || from.isBlank() ? end.minusDays(DEFAULT_RANGE_DAYS) : parseDate(from);
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "date range must be ascending and at most " + MAX_RANGE_DAYS + " days");
        }
        return Result.ok(rebateService.listOwn(userId, start, end));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "dates must be yyyy-MM-dd");
        }
    }
}
