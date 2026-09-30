package com.bingo789.wallet.api.enums;

/**
 * Wallet status. Credits (payouts, rollbacks, refunds) are always accepted, whatever the status,
 * so a player never loses money that is owed to them.
 * <ul>
 *   <li>ACTIVE: everything allowed.</li>
 *   <li>BET_LOCKED: self-exclusion / cool-off / KYC pending. No bets or transfers to providers,
 *   but the player can still withdraw.</li>
 *   <li>FROZEN: AML hold or investigation. No debits at all.</li>
 * </ul>
 * The numeric code is ordered so that SQL can check {@code status <= maxAllowedStatus}.
 */
public enum WalletStatus {

    ACTIVE(1),
    BET_LOCKED(2),
    FROZEN(3);

    private final int code;

    WalletStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static WalletStatus of(int code) {
        for (WalletStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown wallet status " + code);
    }
}
