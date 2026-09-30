package com.bingo789.reconcile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Monthly revenue-share statement per provider and currency. DRAFT is recomputed by the job; CONFIRMED is frozen. */
@Getter
@Setter
@TableName("provider_settlement")
public class ProviderSettlement {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** yyyy-MM in the reporting zone. */
    private String period;
    private String providerCode;
    private String currency;
    private BigDecimal ggr;
    private BigDecimal revenueShareRate;
    private BigDecimal amountDue;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
