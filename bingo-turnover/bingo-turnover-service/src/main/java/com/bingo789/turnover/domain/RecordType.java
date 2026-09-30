package com.bingo789.turnover.domain;

/** Kind of a 稽核记录 (turnover_record). */
public enum RecordType {
    /** Bucket created; amount = required turnover. */
    CREATE,
    /** A settled round's valid bet taken by the bucket; amount = valid bet taken. */
    WAGER,
    /** Bucket cleared (low balance or operator); amount = remainder dropped. */
    CLEAR
}
