package com.bingo789.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** All timestamps are UTC+8. State changes go through the conditional updates in WithdrawOrderMapper. */
@Getter
@Setter
@TableName("withdraw_order")
public class WithdrawOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String orderNo;
    private Long userId;
    /** Player's line when the order was created (snapshot); the order's events carry this value. */
    private Integer userLine;
    private String currency;
    /** Debited from the player: frozen at request time, then confirmed (paid) or unfrozen. */
    private BigDecimal amount;
    private BigDecimal fee;
    private String channelCode;
    /** Token from the payee vault. Raw bank / e-wallet account numbers are never stored in this service. */
    private String payeeRef;
    private WithdrawStatus status;
    private WithdrawAuditCommand.Decision auditDecision;
    private String auditReason;
    private String auditor;
    private String channelOrderNo;
    private String failReason;
    private String clientIp;
    private String deviceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
