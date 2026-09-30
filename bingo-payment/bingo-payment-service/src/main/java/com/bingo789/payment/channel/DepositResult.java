package com.bingo789.payment.channel;

import java.math.BigDecimal;

/**
 * Normalized deposit status from a verified notify or a status query.
 *
 * @param orderNo  our order number, as echoed by the channel (adapters fill it from the order for queries)
 * @param amount   amount the channel says was paid; always compared with the order amount before crediting
 * @param currency may be null when the channel does not report it
 */
public record DepositResult(
        String orderNo,
        String channelOrderNo,
        BigDecimal amount,
        String currency,
        ChannelOutcome outcome,
        String reason) {
}
