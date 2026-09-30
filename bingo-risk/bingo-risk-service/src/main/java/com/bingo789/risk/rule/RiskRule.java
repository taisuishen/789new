package com.bingo789.risk.rule;

/**
 * One withdrawal check; every RiskRule bean is evaluated for every withdrawal (order via {@code @Order}).
 * <p>
 * A rule that cannot decide must throw, never return PASS: the exception aborts the evaluation and the message
 * is redelivered. Side effects are limited to idempotent records keyed by the order (e.g. AML alerts), because
 * a redelivered message may evaluate the rules again before a decision was stored.
 */
public interface RiskRule {

    String code();

    RuleOutcome evaluate(WithdrawContext ctx);
}
