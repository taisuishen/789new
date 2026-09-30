package com.bingo789.payment.domain;

public enum ChannelDirection {
    DEPOSIT,
    PAYOUT,
    BOTH;

    public boolean supports(ChannelDirection required) {
        return this == BOTH || this == required;
    }
}
