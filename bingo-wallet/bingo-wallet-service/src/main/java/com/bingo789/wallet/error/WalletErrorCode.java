package com.bingo789.wallet.error;

import com.bingo789.common.core.ErrorCode;

/** Errors of the wallet's query endpoints. Money commands report outcomes via WalletResultCode instead. */
public enum WalletErrorCode implements ErrorCode {

    WALLET_NOT_FOUND(30001, "wallet not found", 404);

    private final int code;
    private final String message;
    private final int httpStatus;

    WalletErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
