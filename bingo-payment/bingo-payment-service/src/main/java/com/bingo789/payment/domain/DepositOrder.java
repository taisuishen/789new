package com.bingo789.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** All timestamps are UTC+8. State changes go through the conditional updates in DepositOrderMapper. */
@Getter
@Setter
@TableName("deposit_order")
public class DepositOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String orderNo;
    private Long userId;
    /** Player's line when the order was created (snapshot); the order's events carry this value. */
    private Integer userLine;
    private String currency;
    private BigDecimal amount;
    private String channelCode;
    private String channelOrderNo;
    private DepositStatus status;
    private Boolean firstDeposit;
    private String failReason;
    private String clientIp;
    private String deviceId;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime updatedAt;
}
