package com.bingo789.payment.channel;

/**
 * What the player needs in order to pay: a redirect URL and/or QR content (e.g. QR Ph), whichever the channel offers.
 */
public record DepositInitiation(String channelOrderNo, String payUrl, String qrContent) {
}
