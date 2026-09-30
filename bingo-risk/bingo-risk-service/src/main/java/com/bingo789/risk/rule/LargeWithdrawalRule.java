package com.bingo789.risk.rule;

import com.bingo789.risk.config.RiskProperties;
import com.bingo789.risk.domain.AmlAlertType;
import com.bingo789.risk.service.AmlAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Large withdrawals go to manual review and raise an AML alert. The reporting threshold is jurisdiction-specific
 * (licence conditions / AMLC covered-transaction rules) and configured per environment.
 */
@Component
@Order(20)
@RequiredArgsConstructor
public class LargeWithdrawalRule implements RiskRule {

    private final RiskProperties properties;
    private final AmlAlertService amlAlertService;

    @Override
    public String code() {
        return "LARGE_WITHDRAWAL";
    }

    @Override
    public RuleOutcome evaluate(WithdrawContext ctx) {
        BigDecimal threshold = properties.aml().largeWithdrawalThreshold();
        if (ctx.amount().compareTo(threshold) < 0) {
            return RuleOutcome.pass();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("threshold", threshold);
        detail.put("channelCode", ctx.channelCode());
        detail.put("clientIp", ctx.clientIp());
        detail.put("deviceId", ctx.deviceId());
        detail.put("requestedAt", ctx.requestedAt());
        amlAlertService.raise(AmlAlertType.LARGE_WITHDRAWAL, ctx.userId(), ctx.userLine(), ctx.orderNo(), ctx.amount(),
                ctx.currency(), detail);
        return RuleOutcome.review("amount reaches the large-withdrawal threshold " + threshold.stripTrailingZeros().toPlainString());
    }
}
