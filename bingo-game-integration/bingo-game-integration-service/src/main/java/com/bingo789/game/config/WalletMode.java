package com.bingo789.game.config;

public enum WalletMode {
    /** Balance stays on the platform; the provider calls us for every bet/payout. */
    SEAMLESS,
    /** Money is transferred to the provider before play and back afterwards. */
    TRANSFER
}
