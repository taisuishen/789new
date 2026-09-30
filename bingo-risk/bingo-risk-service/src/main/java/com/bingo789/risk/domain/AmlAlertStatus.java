package com.bingo789.risk.domain;

public enum AmlAlertStatus {
    OPEN,
    /** Filed with the regulator (covered / suspicious transaction report). */
    REPORTED,
    CLOSED
}
