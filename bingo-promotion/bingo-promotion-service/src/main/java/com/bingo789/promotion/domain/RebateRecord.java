package com.bingo789.promotion.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One rebate per (statDate, user, currency); the wallet bizNo is "REBATE-" + id. The wagering terms are copied from
 * the promotion when the row is computed, so a later edit of the promotion never changes an earned rebate.
 */
@Getter
@Setter
@TableName("rebate_record")
public class RebateRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private LocalDate statDate;
    private Long userId;
    /** Player's line at the end of the business day (snapshot). */
    private Integer userLine;
    private String currency;
    private BigDecimal validBet;
    private BigDecimal amount;
    /** REBATE promotion whose terms computed this row. */
    private Long promotionId;
    private BigDecimal turnoverMultiplier;
    private String turnoverScope;
    private String turnoverScopeValue;
    private RebateStatus status;
    private String failReason;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
