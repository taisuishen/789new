package com.bingo789.wallet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only ledger row. Rows are never updated or deleted: corrections are new rows
 * (ROLLBACK, PAYOUT_REVERSAL, ADJUST) that reference the original through {@code refTxnId}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("wallet_txn")
public class WalletTxn {

    public static final int STATUS_NORMAL = 1;
    /** A BET row written when its rollback arrived first; a late bet with the same id is rejected. */
    public static final int STATUS_TOMBSTONE = 3;

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    /** Player's line when the row was written (snapshot). */
    private Integer userLine;
    private String currency;
    private String txnType;
    /** -1 debit, 1 credit, 0 no balance change. */
    private Integer direction;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String providerCode;
    private String providerTxnId;
    private String extTxnId;
    private String roundId;
    private String gameCode;
    private String refTxnId;
    private Boolean roundClosed;
    private Integer status;
    private String remark;
    private LocalDateTime createdAt;

    public boolean isTombstone() {
        return status != null && status == STATUS_TOMBSTONE;
    }
}
