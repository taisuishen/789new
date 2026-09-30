package com.bingo789.game.transfer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transfer-wallet order. State machine:
 * <pre>
 * IN  (platform -> provider): INIT -> WALLET_DEBITED -> SUCCEEDED
 *                                   \-> FAILED (wallet refused)    \-> UNKNOWN -> SUCCEEDED | REFUNDED
 *                                                                  \-> REFUNDED (provider refused)
 * OUT (provider -> platform): INIT -> PROVIDER_DONE -> SUCCEEDED
 *                                  \-> FAILED        \-> UNKNOWN -> PROVIDER_DONE | FAILED
 * </pre>
 * Every provider call and wallet call uses orderNo as the idempotency key, so any step can be retried.
 */
@Getter
@Setter
@TableName("transfer_order")
public class TransferOrder {

    public static final String DIRECTION_IN = "IN";
    public static final String DIRECTION_OUT = "OUT";

    public static final String INIT = "INIT";
    public static final String WALLET_DEBITED = "WALLET_DEBITED";
    public static final String PROVIDER_DONE = "PROVIDER_DONE";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String REFUNDED = "REFUNDED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String orderNo;
    private Long userId;
    /** Player's line when the order was created. */
    private Integer userLine;
    private String providerCode;
    private String currency;
    private String direction;
    private BigDecimal amount;
    private String status;
    private String providerRef;
    private Integer attempts;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
