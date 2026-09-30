package com.bingo789.risk.rule;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.risk.config.RiskProperties;
import com.bingo789.risk.mapper.RiskDecisionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/** More than N withdrawal requests within 24 hours (this one included) goes to review. */
@Component
@Order(40)
@RequiredArgsConstructor
public class VelocityRule implements RiskRule {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final RiskDecisionMapper decisionMapper;
    private final RiskProperties properties;

    @Override
    public String code() {
        return "VELOCITY";
    }

    @Override
    public RuleOutcome evaluate(WithdrawContext ctx) {
        LocalDateTime since = BingoTime.toLocal(ctx.requestedAt().minus(WINDOW));
        long total = 1 + MasterRoute.run(() -> decisionMapper.countOthersSince(ctx.userId(), ctx.orderNo(), since));
        int max = properties.velocity().maxWithdrawalsPer24h();
        return total > max
                ? RuleOutcome.review(total + " withdrawal requests within 24h (limit " + max + ")")
                : RuleOutcome.pass();
    }
}
