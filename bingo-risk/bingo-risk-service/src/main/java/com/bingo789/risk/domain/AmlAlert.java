package com.bingo789.risk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** (alertType, refNo) is unique: re-evaluating the same order never raises a second alert. */
@Getter
@Setter
@TableName("aml_alert")
public class AmlAlert {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    /** Player's line from the event that triggered the alert (snapshot). */
    private Integer userLine;
    private AmlAlertType alertType;
    private String refNo;
    private BigDecimal amount;
    private String currency;
    /** JSON object with the facts that triggered the alert. */
    private String detail;
    private AmlAlertStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
