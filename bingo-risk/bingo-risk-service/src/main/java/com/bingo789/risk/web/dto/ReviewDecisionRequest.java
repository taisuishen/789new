package com.bingo789.risk.web.dto;

import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A reason is mandatory for manual decisions (audit trail). */
public record ReviewDecisionRequest(
        @NotNull WithdrawAuditCommand.Decision decision,
        @NotBlank @Size(max = 512) String reason) {
}
