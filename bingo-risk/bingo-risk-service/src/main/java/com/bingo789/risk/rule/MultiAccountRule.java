package com.bingo789.risk.rule;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Detects several player accounts sharing a device or IP.
 * TODO: needs a device/IP association feed built from user-service login logs (e.g. a login event stream
 *  consumed into a risk-owned table). Services must not read each other's databases, so until that feed exists
 *  this rule always passes.
 */
@Component
@Order(50)
public class MultiAccountRule implements RiskRule {

    @Override
    public String code() {
        return "MULTI_ACCOUNT";
    }

    @Override
    public RuleOutcome evaluate(WithdrawContext ctx) {
        return RuleOutcome.pass();
    }
}
