package com.bingo789.game.adapter.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Business result handed back to the adapter for rendering. System failures never become an outcome:
 * they are rendered through {@link CallbackError#SYSTEM_RETRYABLE}.
 *
 * @param platformTxnId our transaction id, for providers that want it echoed
 * @param replay        the request was a duplicate; most specs want the normal success response
 * @param amount        amount of the (last) transaction written, the same on replays: for {@link WalletCommand.TakeAll}
 *                      the stake taken; null when nothing was written
 * @param openBets      the answer to {@link WalletCommand.OpenBets}, empty otherwise
 */
public record CommandOutcome(
        Code code,
        String playerId,
        String currency,
        BigDecimal balance,
        Long platformTxnId,
        boolean replay,
        String message,
        BigDecimal amount,
        List<OpenBet> openBets) {

    public CommandOutcome {
        openBets = openBets == null ? List.of() : List.copyOf(openBets);
    }

    public CommandOutcome(Code code, String playerId, String currency, BigDecimal balance, Long platformTxnId, boolean replay,
                          String message) {
        this(code, playerId, currency, balance, platformTxnId, replay, message, null, List.of());
    }

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
