package com.bingo789.risk.web.dto;

import com.bingo789.payment.api.dto.WithdrawAuditCommand;

/** @param deliveredToPayment false when payment was unreachable; the redelivery job keeps retrying */
public record ReviewDecisionView(String orderNo, WithdrawAuditCommand.Decision decision, boolean deliveredToPayment) {
}
