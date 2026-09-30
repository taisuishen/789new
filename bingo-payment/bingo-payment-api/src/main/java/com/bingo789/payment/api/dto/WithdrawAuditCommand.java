package com.bingo789.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Risk decision on a withdrawal. Idempotent per orderNo: only an order in PENDING_AUDIT changes state.
 *
 * @param auditor "SYSTEM" for automatic decisions, otherwise the back-office operator id
 */
public record WithdrawAuditCommand(
        @NotBlank String orderNo,
        @NotNull Decision decision,
        String reason,
        @NotBlank String auditor) {

    public enum Decision { APPROVED, REJECTED }
}
