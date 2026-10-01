package com.bingo789.game.adapter.model;

import java.math.BigDecimal;

/**
 * Business result handed back to the adapter for rendering. System failures never become an outcome:
 * they are rendered through {@link CallbackError#SYSTEM_RETRYABLE}.
 *
 * @param platformTxnId our transaction id, for providers that want it echoed
 * @param replay        the request was a duplicate; most specs want the normal success response
 */
public record CommandOutcome(
        Code code,
        String playerId,
        String currency,
        BigDecimal balance,
        Long platformTxnId,
        boolean replay,
        String message) {

    public enum Code {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        INVALID_TOKEN,
        PLAYER_NOT_FOUND,
        PLAYER_LOCKED,
        BET_NOT_FOUND,
        TXN_CANCELLED,
        /** Rollback of a bet that is already paid out (reverse the payout first); nothing was changed. */
        BET_SETTLED,
        TXN_NOT_FOUND,
        INVALID_REQUEST
    }

    public static CommandOutcome of(Code code, String playerId, String currency, BigDecimal balance) {
        return new CommandOutcome(code, playerId, currency, balance, null, false, null);
    }

    public boolean isSuccess() {
        return code == Code.SUCCESS;
    }
}
