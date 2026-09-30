package com.bingo789.risk.rule;

import com.bingo789.risk.config.RiskProperties;
import com.bingo789.turnover.api.TurnoverClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Open wagering requirements (deposit play-through, bonus / rebate terms) for the withdrawal currency. */
@Component
@Order(10)
@RequiredArgsConstructor
public class TurnoverRequirementRule implements RiskRule {

    private final TurnoverClient turnoverClient;
    private final RiskProperties properties;

    @Override
    public String code() {
        return "TURNOVER_REQUIREMENT";
    }

    @Override
    public RuleOutcome evaluate(WithdrawContext ctx) {
        // owned by bingo-turnover; a failed call fails the evaluation (the withdrawal is retried), never passes it
        BigDecimal outstanding = turnoverClient.outstanding(ctx.userId(), ctx.currency()).remaining();
        if (outstanding.signum() <= 0) {
            return RuleOutcome.pass();
        }
        String reason = "outstanding wagering requirement " + outstanding.stripTrailingZeros().toPlainString() + " " + ctx.currency();
        return properties.turnover().rejectWhenOutstanding() ? RuleOutcome.reject(reason) : RuleOutcome.review(reason);
    }
}
