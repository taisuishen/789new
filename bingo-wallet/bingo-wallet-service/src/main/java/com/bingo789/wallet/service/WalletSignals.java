package com.bingo789.wallet.service;

import com.bingo789.wallet.api.enums.WalletResultCode;
import com.bingo789.wallet.api.enums.WalletStatus;

/**
 * Control-flow signals thrown out of {@link WalletTxnExecutor} so that the transaction rolls back and
 * {@link WalletService} decides the response. Stack traces are disabled: these are hot-path, expected outcomes.
 */
final class WalletSignals {

    private WalletSignals() {
    }

    /** Business refusal. A null code means "debit refused", the precise reason is derived from the wallet row. */
    static final class Rejected extends RuntimeException {

        private final WalletResultCode code;
        private final WalletStatus maxStatus;

        private Rejected(WalletResultCode code, WalletStatus maxStatus, String message) {
            super(message, null, false, false);
            this.code = code;
            this.maxStatus = maxStatus;
        }

        static Rejected debitRefused(WalletStatus maxStatus) {
            return new Rejected(null, maxStatus, null);
        }

        static Rejected of(WalletResultCode code, String message) {
            return new Rejected(code, WalletStatus.FROZEN, message);
        }

        WalletResultCode code() {
            return code;
        }

        WalletStatus maxStatus() {
            return maxStatus;
        }
    }

    /** The operation was already applied (e.g. repeated rollback of a tombstoned bet): answer with the first result. */
    static final class AlreadyApplied extends RuntimeException {
        AlreadyApplied() {
            super(null, null, false, false);
        }
    }

    /** Lost a race with a concurrent operation on the same target; re-run the whole operation. */
    static final class Retry extends RuntimeException {
        Retry(String message) {
            super(message, null, false, false);
        }
    }
}
