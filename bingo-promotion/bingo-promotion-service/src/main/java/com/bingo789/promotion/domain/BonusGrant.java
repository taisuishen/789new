package com.bingo789.promotion.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * bizNo is unique and is also the wallet bizNo, so a grant is paid at most once. Line and wagering terms are stored
 * with the grant and used whenever its BonusGrantedEvent is (re)published.
 */
@Getter
@Setter
@TableName("bonus_grant")
public class BonusGrant {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String bizNo;
    private Long userId;
    /** Player's line from the triggering event (snapshot). */
    private Integer userLine;
    private String currency;
    private BigDecimal amount;
    private String bonusType;
    /** Promotion whose terms computed the grant (attribution). */
    private Long promotionId;
    private BonusStatus status;
    private BigDecimal turnoverMultiplier;
    private String turnoverScope;
    private String turnoverScopeValue;
    private String failReason;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime updatedAt;
}
