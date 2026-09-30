package com.bingo789.turnover.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("turnover_setting")
public class TurnoverSetting {

    private Integer userLine;
    private String currency;
    /** Null = rule off. */
    private BigDecimal clearBelowBalance;
    /** Null = rule off. */
    private BigDecimal completeBelowRemaining;
    private BigDecimal depositMultiplier;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
