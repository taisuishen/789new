package com.bingo789.wallet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row per (user, currency). Clustered on (user_id, currency); all timestamps are UTC+8. */
@Getter
@Setter
@TableName("wallet")
public class Wallet {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private String currency;
    /** Copy of the player's line; stamped on every ledger row. */
    private Integer userLine;
    private BigDecimal balance;
    private BigDecimal frozen;
    private Integer status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
