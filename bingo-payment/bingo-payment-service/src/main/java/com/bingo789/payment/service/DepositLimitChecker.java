package com.bingo789.payment.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.payment.common.PaymentErrorCode;
import com.bingo789.payment.common.RemoteCalls;
import com.bingo789.payment.config.PaymentProperties;
import com.bingo789.payment.mapper.DepositOrderMapper;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.RgLimitsView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Responsible-gaming deposit limits. The limits are set by the player in user-service; the totals are this
 * service's own CREATED + PENDING + SUCCEEDED deposits (in-flight orders count, so parallel deposits cannot
 * overshoot). Windows are calendar day / ISO week (Monday) / month in the business time zone.
 * Callers must hold the per-player deposit lock so check and insert are atomic.
 */
@Component
@RequiredArgsConstructor
public class DepositLimitChecker {

    private final UserClient userClient;
    private final DepositOrderMapper depositMapper;
    private final PaymentProperties properties;

    public void check(long userId, String currency, BigDecimal amount) {
        RgLimitsView limits = RemoteCalls.call("user.rgLimits", () -> userClient.rgLimits(userId));
        ZoneId zone = properties.zone();
        LocalDate today = LocalDate.now(zone);
        checkWindow(userId, currency, amount, limits.dailyDepositLimit(), today, zone, "daily");
        checkWindow(userId, currency, amount, limits.weeklyDepositLimit(),
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), zone, "weekly");
        checkWindow(userId, currency, amount, limits.monthlyDepositLimit(), today.withDayOfMonth(1), zone, "monthly");
    }

    private void checkWindow(long userId, String currency, BigDecimal amount, BigDecimal limit,
                             LocalDate windowStart, ZoneId zone, String window) {
        if (limit == null) {
            return;
        }
        LocalDateTime since = BingoTime.toLocal(windowStart.atStartOfDay(zone).toInstant());
        // primary node: the order this player created a moment ago must be counted
        BigDecimal used = MasterRoute.run(() -> depositMapper.sumTowardsLimits(userId, currency, since));
        if (used.add(amount).compareTo(limit) > 0) {
            BigDecimal remaining = limit.subtract(used).max(BigDecimal.ZERO);
            throw new BizException(PaymentErrorCode.DEPOSIT_LIMIT_EXCEEDED,
                    window + " deposit limit reached, remaining " + remaining.stripTrailingZeros().toPlainString());
        }
    }
}
