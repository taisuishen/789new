package com.bingo789.common.mq.event;

import com.bingo789.common.core.line.UserLine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One committed wallet_txn row, as emitted by the Flink CDC job (deploy/flink/wallet_txn_cdc.sql).
 * Field names must stay in sync with that job.
 *
 * @param direction -1 debit, 1 credit, 0 no balance change (tombstones, zero payouts)
 * @param status    1 normal, 3 tombstone (bet cancelled before it arrived)
 */
public record WalletTxnEvent(
        long id,
        long userId,
        Integer userLine,
        String currency,
        String txnType,
        int direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String providerCode,
        String providerTxnId,
        String roundId,
        String gameCode,
        String refTxnId,
        boolean roundClosed,
        int status,
        Instant createdAt) {

    /** A message written before the field existed carries no line: it belongs to line 1. */
    public WalletTxnEvent {
        userLine = UserLine.orDefault(userLine);
    }
}
