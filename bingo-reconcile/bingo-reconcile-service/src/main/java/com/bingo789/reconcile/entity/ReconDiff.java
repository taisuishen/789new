package com.bingo789.reconcile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A reconciliation difference / ticket. diff = platform_value - provider_value. Times are UTC+8. */
@Getter
@Setter
@TableName("recon_diff")
public class ReconDiff {

    @TableId(type = IdType.AUTO)
    private Long id;
    private ReconLevel level;
    private String providerCode;
    private String currency;
    private LocalDateTime periodStart;
    private String metric;
    private BigDecimal platformValue;
    private BigDecimal providerValue;
    private BigDecimal diff;
    private DiffStatus status;
    private String note;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
