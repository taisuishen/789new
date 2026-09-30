package com.bingo789.promotion.domain;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Row of valid_bet_daily (composite primary key, written only by upsert), also used as the batch upsert parameter.
 * Not a MyBatis-Plus entity.
 */
@Getter
@Setter
public class ValidBetDaily {

    private LocalDate statDate;
    private Long userId;
    /** Line of the player's latest round applied to this user-day; not part of the key. */
    private Integer userLine;
    private String currency;
    private String providerCode;
    private BigDecimal validBet;
    private Integer roundCount;
    /** Read only (set by the database). */
    private LocalDateTime updatedAt;
}
