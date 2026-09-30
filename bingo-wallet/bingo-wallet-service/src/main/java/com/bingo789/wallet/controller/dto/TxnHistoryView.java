package com.bingo789.wallet.controller.dto;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.wallet.domain.WalletTxn;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of the player's transaction history.
 *
 * @param direction -1 debit, 1 credit, 0 no balance change
 */
public record TxnHistoryView(
        String id,
        String currency,
        String txnType,
        int direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String gameCode,
        String roundId,
        Instant createdAt) {

    public static TxnHistoryView of(WalletTxn txn) {
        return new TxnHistoryView(String.valueOf(txn.getId()), txn.getCurrency(), txn.getTxnType(), txn.getDirection(),
                txn.getAmount(), txn.getBalanceAfter(), txn.getGameCode(), txn.getRoundId(),
                txn.getCreatedAt().atZone(BingoTime.ZONE).toInstant());
    }
}
