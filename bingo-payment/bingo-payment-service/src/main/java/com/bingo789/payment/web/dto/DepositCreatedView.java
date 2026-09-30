package com.bingo789.payment.web.dto;

import com.bingo789.payment.domain.DepositStatus;

import java.math.BigDecimal;

public record DepositCreatedView(
        String orderNo,
        BigDecimal amount,
        String currency,
        DepositStatus status,
        String payUrl,
        String qrContent) {
}
