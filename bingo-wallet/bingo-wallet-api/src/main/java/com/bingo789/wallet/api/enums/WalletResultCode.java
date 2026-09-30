package com.bingo789.wallet.api.enums;

/**
 * Business outcome of a wallet command. Returned with HTTP 200; system failures are HTTP 5xx and must
 * be reported to providers as "system error, retry", never as success.
 */
public enum WalletResultCode {

    SUCCESS,
    INSUFFICIENT_FUNDS,
    WALLET_NOT_FOUND,
    WALLET_LOCKED,
    /** Payout without a live bet in the round; most provider specs expect a retryable error here. */
    BET_NOT_FOUND,
    /** Bet arrived after its rollback (tombstone present); the bet is rejected. */
    BET_CANCELLED,
    /** Rollback/adjust target that must exist does not. */
    TXN_NOT_FOUND,
    INVALID_REQUEST;

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
