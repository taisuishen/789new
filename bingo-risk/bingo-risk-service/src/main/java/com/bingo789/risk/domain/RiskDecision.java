package com.bingo789.risk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Rules outcome for one withdrawal plus the final decision sent to payment.
 * finalDecision is set immediately for PASS / REJECT and by the operator for REVIEW.
 */
@Getter
@Setter
@TableName("risk_decision")
public class RiskDecision {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String orderNo;
    private Long userId;
    /** Player's line from the withdraw-requested event (snapshot). */
    private Integer userLine;
    private String currency;
    private BigDecimal amount;
    private Verdict verdict;
    /** JSON array of {@link com.bingo789.risk.rule.RuleHit}. */
    private String ruleHits;
    private WithdrawAuditCommand.Decision finalDecision;
    private String finalReason;
    private String auditor;
    private Boolean delivered;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
