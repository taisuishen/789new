package com.bingo789.payment.channel;

/**
 * Normalized result of a deposit or payout at the channel.
 * <p>
 * Adapters must map to {@link #FAILED} only when the provider guarantees that the money did not move and never
 * will for this order. Everything uncertain (processing, timeout, "order not found" right after submission,
 * unknown provider codes) is {@link #PENDING}.
 */
public enum ChannelOutcome {
    SUCCEEDED,
    FAILED,
    PENDING
}
