package com.bingo789.risk.domain;

/** Rule outcome, declared in increasing severity: the aggregate verdict is the most severe one. */
public enum Verdict {
    PASS,
    REVIEW,
    REJECT
}
