package com.bingo789.game.adapter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A token-bound stake that is not settled yet, as a provider's "open rounds" query needs it.
 *
 * @param amount   the stake; null when the outcome of a take-all is not known yet (the wallet call did not answer)
 * @param token    the game token the stake was placed with: it keeps identifying the player for the payout or refund
 *                 of this stake after it expired
 * @param gameCode the game the provider named on the stake, if any
 * @param placedAt when we received the stake
 * @param pending  the wallet call did not answer: the stake may or may not have been debited
 */
public record OpenBet(String txnId, String roundId, BigDecimal amount, String token, String gameCode, Instant placedAt,
                      boolean pending) {
}
