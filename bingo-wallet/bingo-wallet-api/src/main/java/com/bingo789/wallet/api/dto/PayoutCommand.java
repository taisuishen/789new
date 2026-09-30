package com.bingo789.wallet.api.dto;

import com.bingo789.wallet.api.enums.TxnType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Credit a win. Zero amounts are legal and still recorded, because a zero payout is what closes a
 * losing round. Idempotency key: (providerCode, providerTxnId, payoutType).
 *
 * @param payoutType PAYOUT, FREE_PAYOUT, JACKPOT_PAYOUT or PROMO_PAYOUT
 * @param betTxnId   optional: the specific bet this payout settles; otherwise any live bet in the round
 * @param requireBet provider-specific: reject a normal PAYOUT with BET_NOT_FOUND when the round has no live bet.
 *                   Ignored for stakeless payout types.
 */
public record PayoutCommand(
        @NotNull Long userId,
        @NotBlank String currency,
        @NotBlank String providerCode,
        @NotBlank String providerTxnId,
        @NotBlank String roundId,
        String gameCode,
        @NotNull BigDecimal amount,
        @NotNull TxnType payoutType,
        String betTxnId,
        boolean requireBet,
        boolean roundClosed) {
}
