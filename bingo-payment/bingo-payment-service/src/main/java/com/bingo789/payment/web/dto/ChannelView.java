package com.bingo789.payment.web.dto;

import com.bingo789.payment.domain.ChannelConfig;
import com.bingo789.payment.domain.ChannelDirection;

import java.math.BigDecimal;

public record ChannelView(
        String code,
        String name,
        ChannelDirection direction,
        String currencies,
        BigDecimal minAmount,
        BigDecimal maxAmount) {

    public static ChannelView of(ChannelConfig config) {
        return new ChannelView(config.getCode(), config.getName(), config.getDirection(), config.getCurrencies(),
                config.getMinAmount(), config.getMaxAmount());
    }
}
