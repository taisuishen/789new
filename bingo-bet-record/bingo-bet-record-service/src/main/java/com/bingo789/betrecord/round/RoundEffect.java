package com.bingo789.betrecord.round;

import com.bingo789.common.mq.event.WalletTxnEvent;
import com.bingo789.wallet.api.enums.TxnType;

import java.math.BigDecimal;

/**
 * How a wallet ledger row changes its game round. Platform transactions (deposits, withdrawals, bonuses,
 * transfers) and tombstones have no effect.
 */
public enum RoundEffect {

    BET,
    PAYOUT,
    /** Reverses a bet; the wallet copies the bet's round id onto the rollback row. */
    ROLLBACK,
    PAYOUT_REVERSAL,
    /** TODO: adjustments are modelled simply as a signed payout delta; provider re-settlement may need its own columns. */
    ADJUST;

    private static final int STATUS_NORMAL = 1;

    /** Null when the row does not affect a round. */
    public static RoundEffect of(WalletTxnEvent event) {
        // status 3 = tombstone BET written when a rollback arrived before its bet (amount 0)
        if (event.status() != STATUS_NORMAL || event.roundId() == null || event.roundId().isBlank()
                || event.providerCode() == null || event.txnType() == null) {
            return null;
        }
        TxnType type;
        try {
            type = TxnType.valueOf(event.txnType());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return switch (type) {
            case BET -> BET;
            case PAYOUT, FREE_PAYOUT, JACKPOT_PAYOUT, PROMO_PAYOUT -> PAYOUT;
            case ROLLBACK -> ROLLBACK;
            case PAYOUT_REVERSAL -> PAYOUT_REVERSAL;
            case ADJUST -> ADJUST;
            default -> null;
        };
    }

    /** Settlements and reversals may belong to a round opened days ago; bets open rounds. */
    public boolean mayTargetOldRound() {
        return this != BET;
    }

    /** Reversals of something that was never recorded have nothing to undo. */
    public boolean requiresExistingRound() {
        return this == ROLLBACK || this == PAYOUT_REVERSAL || this == ADJUST;
    }

    public Delta delta(WalletTxnEvent event) {
        BigDecimal amount = event.amount() == null ? BigDecimal.ZERO : event.amount();
        return switch (this) {
            case BET -> new Delta(amount, BigDecimal.ZERO, 1, 0);
            case PAYOUT -> new Delta(BigDecimal.ZERO, amount, 0, 1);
            case ROLLBACK -> new Delta(amount.negate(), BigDecimal.ZERO, -1, 0);
            case PAYOUT_REVERSAL -> new Delta(BigDecimal.ZERO, amount.negate(), 0, -1);
            // direction: +1 credit to the player (raises payout), -1 debit, 0 no balance change
            case ADJUST -> new Delta(BigDecimal.ZERO,
                    amount.multiply(BigDecimal.valueOf(Integer.signum(event.direction()))), 0, 0);
        };
    }

    public record Delta(BigDecimal bet, BigDecimal payout, int betCount, int payoutCount) {
    }
}
