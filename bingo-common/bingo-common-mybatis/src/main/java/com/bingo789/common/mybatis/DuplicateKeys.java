package com.bingo789.common.mybatis;

import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

/**
 * Detects unique-index violations regardless of which exception translator produced the exception.
 * Unique indexes are the last line of defence for idempotency, so this check must be reliable.
 */
public final class DuplicateKeys {

    private static final int MYSQL_ER_DUP_ENTRY = 1062;

    private DuplicateKeys() {
    }

    public static boolean isDuplicateKey(Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            if (current instanceof SQLException sql && sql.getErrorCode() == MYSQL_ER_DUP_ENTRY) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
