package com.bingo789.wallet.api.dto;

import com.bingo789.wallet.api.enums.TxnType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Money movements initiated by the platform itself (payment, promotion, transfer-wallet).
 * Idempotency key: (source, bizNo, txnType).
 *
 * @param source system that owns bizNo, e.g. PAYMENT, PROMOTION, TRANSFER:{provider}
 */
public record PlatformTxnCommand(
        @NotNull Long userId,
        @NotBlank String currency,
        @NotNull TxnType txnType,
        @NotBlank String source,
        @NotBlank String bizNo,
        @NotNull BigDecimal amount,
        String remark) {
}
