package com.bingo789.common.core.line;

/**
 * User line ("线路"): a partition of players that decides which back-office staff and reports may see them.
 * Every player-related business row stores the line its player had when the row was written (a snapshot:
 * moving a player to another line never rewrites history, the ledger is append-only and past reports stay
 * stable). New players are on {@link #DEFAULT}.
 */
public final class UserLine {

    public static final int DEFAULT = 1;
    public static final int MIN = 1;
    public static final int MAX = 99;

    private UserLine() {
    }

    public static boolean isValid(int line) {
        return line >= MIN && line <= MAX;
    }

    /**
     * Messages written before the field existed (Kafka retention, replays) carry no line: they belong to
     * {@link #DEFAULT}, the only line that existed then.
     */
    public static int orDefault(Integer line) {
        return line == null || line < MIN ? DEFAULT : line;
    }

    public static int require(int line) {
        if (!isValid(line)) {
            throw new IllegalArgumentException("user line must be between " + MIN + " and " + MAX + ": " + line);
        }
        return line;
    }
}
