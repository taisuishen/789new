package com.bingo789.wallet.api.dto;

import com.bingo789.wallet.api.enums.WalletResultCode;

import java.math.BigDecimal;

/**
 * @param txnId           platform transaction id (the id providers may ask us to echo back)
 * @param balance         current available balance at response time
 * @param txnBalanceAfter balance right after this transaction was first applied
 * @param replay          true when the request was a duplicate and the first result is returned
 */
public record WalletResult(
        WalletResultCode code,
        Long txnId,
        String currency,
        BigDecimal balance,
        BigDecimal txnBalanceAfter,
        boolean replay,
        String message) {

    public static WalletResult success(long txnId, String currency, BigDecimal balance, BigDecimal txnBalanceAfter) {
        return new WalletResult(WalletResultCode.SUCCESS, txnId, currency, balance, txnBalanceAfter, false, null);
    }

    public static WalletResult replay(long txnId, String currency, BigDecimal balance, BigDecimal txnBalanceAfter) {
        return new WalletResult(WalletResultCode.SUCCESS, txnId, currency, balance, txnBalanceAfter, true, null);
    }

    public static WalletResult reject(WalletResultCode code, String currency, BigDecimal balance, String message) {
        return new WalletResult(code, null, currency, balance, null, false, message);
    }

    public boolean isSuccess() {
        return code != null && code.isSuccess();
    }
}
