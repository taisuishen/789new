package com.bingo789.wallet.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Debit the player's whole available balance as one stake (CQ9 takeall, YGR rollOut with takeAll). The stake is a
 * normal BET row, so payouts and rollbacks of it work as for any bet; the amount taken is
 * {@link WalletResult#txnAmount()}, also on replays. Idempotency key: (providerCode, providerTxnId, BET).
 *
 * @param scale decimals the provider is shown: the balance rounded DOWN to this scale is taken, a smaller remainder
 *              stays in the wallet (the provider sees the remaining balance as 0 at that scale)
 */
public record TakeAllBetCommand(
        @NotNull Long userId,
        @NotBlank String currency,
        @NotBlank String providerCode,
        @NotBlank String providerTxnId,
        @NotBlank String roundId,
        String gameCode,
        @Min(0) @Max(4) int scale) {
}
