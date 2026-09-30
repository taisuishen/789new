package com.bingo789.betrecord.entity;

/** OPEN until the provider closes the round (SETTLED) or every bet of it is rolled back (CANCELLED). */
public enum RoundStatus {
    OPEN,
    SETTLED,
    CANCELLED;

    public boolean isTerminal() {
        return this != OPEN;
    }
}
