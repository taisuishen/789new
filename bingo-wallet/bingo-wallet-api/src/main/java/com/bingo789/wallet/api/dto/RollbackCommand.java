package com.bingo789.wallet.api.dto;

import com.bingo789.wallet.api.enums.TxnType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Reverse a bet (or a payout).
 * <ul>
 *   <li>With {@code targetTxnId}: reverse exactly that transaction. If it has not arrived yet a
 *   tombstone is written, and the late bet will be rejected with BET_CANCELLED.</li>
 *   <li>Without {@code targetTxnId}: reverse every live bet of {@code roundId}.</li>
 * </ul>
 * A target is reversed at most once, whatever the number of distinct rollback requests.
 *
 * @param rollbackTxnId the provider's id of this rollback request (stored for audit)
 * @param targetType    BET (default) or one of the payout types
 */
public record RollbackCommand(
        @NotNull Long userId,
        @NotBlank String currency,
        @NotBlank String providerCode,
        @NotBlank String rollbackTxnId,
        String targetTxnId,
        TxnType targetType,
        String roundId,
        String gameCode) {

    public TxnType effectiveTargetType() {
        return targetType == null ? TxnType.BET : targetType;
    }
}
