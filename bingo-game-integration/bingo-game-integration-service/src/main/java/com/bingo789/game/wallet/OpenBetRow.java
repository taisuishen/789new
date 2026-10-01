package com.bingo789.game.wallet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A tracked token-bound stake, see {@link OpenBetLedger}. */
@Getter
@Setter
@TableName("open_bet")
public class OpenBetRow {

    /** Written before the wallet call; the stake may or may not be debited. */
    public static final String PENDING = "PENDING";
    /** Debited, not paid out or refunded yet. */
    public static final String OPEN = "OPEN";
    /** Paid out, refunded, or refused by the wallet. */
    public static final String CLOSED = "CLOSED";

    @TableId(type = IdType.INPUT)
    private Long id;
    private String providerCode;
    private String txnId;
    private String roundId;
    private Long userId;
    private String currency;
    private String gameCode;
    private String sessionToken;
    /** Null while a take-all stake is PENDING. */
    private BigDecimal amount;
    private String status;
    /** UTC+8. */
    private LocalDateTime placedAt;
    private LocalDateTime updatedAt;
}
