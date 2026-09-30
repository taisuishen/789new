package com.bingo789.payment.channel;

/**
 * Normalized payout status from a submission response, a verified notify or a status query.
 * See {@link ChannelOutcome} for when an adapter may report FAILED.
 */
public record PayoutResult(
        String orderNo,
        String channelOrderNo,
        ChannelOutcome outcome,
        String reason) {
}
