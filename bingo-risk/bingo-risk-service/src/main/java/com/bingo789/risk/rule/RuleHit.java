package com.bingo789.risk.rule;

import com.bingo789.risk.domain.Verdict;

/** A non-PASS rule outcome, stored as JSON in risk_decision.rule_hits for audit. */
public record RuleHit(String rule, Verdict verdict, String reason) {
}
