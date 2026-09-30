package com.bingo789.turnover.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bingo789.common.core.game.TurnoverScope;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("turnover_bucket")
public class TurnoverBucket {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Integer userLine;
    private String currency;
    private TurnoverScope scopeType;
    /** "" for ALL. */
    private String scopeValue;
    /** 1 GAME, 2 GAME_TYPE, 3 ALL. */
    private Integer scopeRank;
    private SourceType sourceType;
    private String sourceNo;
    private BigDecimal baseAmount;
    private BigDecimal multiplier;
    private BigDecimal requiredAmount;
    private BigDecimal achievedAmount;
    private BucketStatus status;
    private CloseReason closeReason;
    private String closedBy;
    private Integer version;
    /** Time of the source event: only rounds whose first bet is not earlier count towards the bucket. */
    private LocalDateTime createdAt;
    private LocalDateTime closedAt;
    private LocalDateTime updatedAt;

    public BigDecimal remaining() {
        BigDecimal left = requiredAmount.subtract(achievedAmount);
        return left.signum() > 0 ? left : BigDecimal.ZERO;
    }

    public static int rankOf(TurnoverScope scope) {
        return switch (scope) {
            case GAME -> 1;
            case GAME_TYPE -> 2;
            case ALL -> 3;
        };
    }
}
