package com.bingo789.reconcile.entity;

/**
 * OPEN and AUTO_FIXED are under automation control (a re-run may update or reopen them);
 * RESOLVED and IGNORED are operator decisions and final.
 */
public enum DiffStatus {
    OPEN,
    AUTO_FIXED,
    RESOLVED,
    IGNORED
}
