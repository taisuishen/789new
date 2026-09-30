package com.bingo789.risk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("risk_review_task")
public class RiskReviewTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String orderNo;
    private Long userId;
    /** Player's line from the withdraw-requested event (snapshot). */
    private Integer userLine;
    private String currency;
    private BigDecimal amount;
    private String reasons;
    private ReviewStatus status;
    private String operator;
    private String decisionReason;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
