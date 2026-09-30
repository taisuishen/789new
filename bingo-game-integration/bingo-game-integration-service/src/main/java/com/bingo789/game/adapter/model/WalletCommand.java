package com.bingo789.game.adapter.model;

import com.bingo789.wallet.api.enums.TxnType;

import java.math.BigDecimal;

/**
 * The platform's unified callback vocabulary. Every adapter translates its provider's messages into
 * exactly one of these; the rest of the pipeline never sees provider formats.
 * {@code playerId} is the external id we gave the provider (see PlayerIds).
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
}
