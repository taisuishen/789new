package com.bingo789.wallet.api.enums;

/**
 * Wallet transaction types. Together with (provider_code, provider_txn_id) the type forms the
 * idempotency key, so providers that reuse one transaction id for debit and credit still work.
 */
public enum TxnType {

    // ----- seamless-wallet game transactions -----
    BET(Category.GAME),
    PAYOUT(Category.GAME),
    /** Win from free spins: no stake of its own. */
    FREE_PAYOUT(Category.GAME),
    JACKPOT_PAYOUT(Category.GAME),
    /** Provider-side promotion / tournament prize. */
    PROMO_PAYOUT(Category.GAME),
    /** Reverses a BET (credit). One per bet; idempotency key is the bet's provider txn id. */
    ROLLBACK(Category.GAME),
    /** Reverses a payout (debit). One per payout. */
    PAYOUT_REVERSAL(Category.GAME),
    /** Provider re-settlement or manual correction, signed. Never edits the original row. */
    ADJUST(Category.GAME),

    // ----- platform transactions -----
    DEPOSIT(Category.PLATFORM),
    WITHDRAW_FREEZE(Category.PLATFORM),
    WITHDRAW_CONFIRM(Category.PLATFORM),
    WITHDRAW_UNFREEZE(Category.PLATFORM),
    BONUS(Category.PLATFORM),
    REBATE(Category.PLATFORM),
    /** Transfer-wallet mode: platform -> provider. */
    TRANSFER_OUT(Category.PLATFORM),
    /** Transfer-wallet mode: provider -> platform, or refund of a failed TRANSFER_OUT. */
    TRANSFER_IN(Category.PLATFORM);

    public enum Category { GAME, PLATFORM }

    private final Category category;

    TxnType(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    public boolean isPayout() {
        return this == PAYOUT || this == FREE_PAYOUT || this == JACKPOT_PAYOUT || this == PROMO_PAYOUT;
    }

    /** Payouts that are paid without a stake and therefore never require a matching bet. */
    public boolean isStakeless() {
        return this == FREE_PAYOUT || this == JACKPOT_PAYOUT || this == PROMO_PAYOUT;
    }
}
