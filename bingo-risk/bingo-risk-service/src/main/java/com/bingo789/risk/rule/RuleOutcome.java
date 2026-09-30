package com.bingo789.risk.rule;

import com.bingo789.risk.domain.Verdict;

public record RuleOutcome(Verdict verdict, String reason) {

    private static final RuleOutcome PASS = new RuleOutcome(Verdict.PASS, null);

    public static RuleOutcome pass() {
        return PASS;
    }

    public static RuleOutcome review(String reason) {
        return new RuleOutcome(Verdict.REVIEW, reason);
    }

    public static RuleOutcome reject(String reason) {
        return new RuleOutcome(Verdict.REJECT, reason);
    }
}
