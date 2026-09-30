package com.bingo789.game.adapter.model;

import com.bingo789.wallet.api.enums.TxnType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Provider's view of one round, used to settle rounds that stayed open too long.
 *
 * @param payouts wins the provider says it paid; applied through the wallet with the provider's own txn ids,
 *                so ones we already have simply replay
 */
public record RoundStatus(State state, List<Settlement> payouts) {

    public enum State { IN_PROGRESS, COMPLETED, CANCELLED, UNKNOWN }

    public record Settlement(String txnId, BigDecimal amount, TxnType payoutType) {
    }

    public static RoundStatus unknown() {
        return new RoundStatus(State.UNKNOWN, List.of());
    }
}
