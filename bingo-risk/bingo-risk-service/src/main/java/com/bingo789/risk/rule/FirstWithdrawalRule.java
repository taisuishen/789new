package com.bingo789.risk.rule;

import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.risk.mapper.RiskDecisionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A player's first withdrawal is always reviewed (payee account ownership, KYC match).
 * "First" = no other withdrawal request evaluated by risk before, whatever its outcome.
 */
@Component
@Order(30)
@RequiredArgsConstructor
public class FirstWithdrawalRule implements RiskRule {

    private final RiskDecisionMapper decisionMapper;

    @Override
    public String code() {
        return "FIRST_WITHDRAWAL";
    }

    @Override
    public RuleOutcome evaluate(WithdrawContext ctx) {
        long previous = MasterRoute.run(() -> decisionMapper.countOthers(ctx.userId(), ctx.orderNo()));
        return previous == 0 ? RuleOutcome.review("first withdrawal of the player") : RuleOutcome.pass();
    }
}
