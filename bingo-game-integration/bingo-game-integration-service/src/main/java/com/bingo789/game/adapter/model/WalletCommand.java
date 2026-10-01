package com.bingo789.game.adapter.model;

import com.bingo789.wallet.api.enums.TxnType;

import java.math.BigDecimal;
import java.util.List;

/**
 * The platform's unified callback vocabulary. Every adapter translates its provider's messages into
 * exactly one of these; the rest of the pipeline never sees provider formats.
 * {@code playerId} is the external id we gave the provider (see PlayerIds).
 * <p>
 * A {@code currency} may be null when the provider does not send one: the gateway then uses the game session's
 * currency ({@link Session}) or the provider's first configured currency.
 */
public sealed interface WalletCommand {

    String currency();

    /** Provider presents the launch token; we answer with player id, currency and balance. */
    record Authenticate(String token, String currency) implements WalletCommand {
    }

    record GetBalance(String playerId, String currency) implements WalletCommand {
    }

    record Bet(String playerId, String currency, String txnId, String roundId, String gameCode,
               BigDecimal amount, boolean roundClosed) implements WalletCommand {
    }

    /** @param payoutType PAYOUT, FREE_PAYOUT, JACKPOT_PAYOUT or PROMO_PAYOUT */
    record Payout(String playerId, String currency, String txnId, String roundId, String gameCode,
                  BigDecimal amount, TxnType payoutType, String betTxnId, boolean roundClosed) implements WalletCommand {
    }

    record BetAndPayout(String playerId, String currency, String betTxnId, String payoutTxnId, String roundId,
                        String gameCode, BigDecimal betAmount, BigDecimal payoutAmount, boolean roundClosed) implements WalletCommand {
    }

    /** @param targetTxnId null = cancel the whole round */
    record Rollback(String playerId, String currency, String rollbackTxnId, String targetTxnId, TxnType targetType,
                    String roundId, String gameCode) implements WalletCommand {
    }

    record Adjust(String playerId, String currency, String txnId, String refTxnId, String roundId,
                  BigDecimal signedAmount, String reason) implements WalletCommand {
    }

    /**
     * A command of the player who holds this game token, for providers that identify the player (or authenticate the
     * call) by the launch token instead of, or in addition to, a player id. The gateway verifies the token first;
     * the inner command may carry {@code playerId = null} (taken from the token) or the provider's player id, which
     * must then belong to the token.
     */
    record Session(String token, WalletCommand command) implements WalletCommand {

        @Override
        public String currency() {
            return command.currency();
        }
    }

    /**
     * Several wallet operations of one player in one callback (e.g. one call settling N bets, or voiding a settled
     * round). Executed in order, stopping at the first one that does not succeed; every step is idempotent on its
     * own transaction id, so the provider's retry of a partly applied batch completes it. Steps are plain commands of
     * the same player (their playerId / currency are ignored) and may not be batches or sessions.
     * To void a round: reverse the PAYOUT first, then the BET (the wallet refuses to refund a paid-out bet); a payout
     * reversal whose payout does not exist is skipped.
     */
    record Batch(String playerId, String currency, List<WalletCommand> steps) implements WalletCommand {

        public Batch {
            steps = List.copyOf(steps);
            if (steps.isEmpty() || steps.stream().anyMatch(s -> s instanceof Batch || s instanceof Session)) {
                throw new IllegalArgumentException("a batch needs plain steps");
            }
        }
    }

    /** A callback that moves no money (end of round, bet details, heartbeat): answered SUCCESS without a wallet call. */
    record Ack(String playerId, String currency) implements WalletCommand {
    }
}
