package com.bingo789.betrecord.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One player's round at one provider. Primary key is (id, round_date) because the table is partitioned by
 * round_date; every update statement carries both so it prunes to a single partition.
 * Amounts are net: bet_amount = bets - rollbacks, payout_amount = payouts - reversals + adjustments.
 * All timestamps are UTC+8.
 */
@Getter
@Setter
@TableName("game_round")
public class GameRound {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String providerCode;
    private String roundId;
    private Long userId;
    /** Player's line when the round started (first event); never changed afterwards. */
    private Integer userLine;
    private String currency;
    private String gameCode;
    /** GameType name from the lobby catalogue when the round started (OTHER if unknown); never changed afterwards. */
    private String gameType;
    /** Catalogue name when the round started (the game code if unknown); never changed afterwards. */
    private String gameName;
    private BigDecimal betAmount;
    private BigDecimal payoutAmount;
    /** Live bets: +1 per bet, -1 per rollback. */
    private Integer betCount;
    /** Live payouts: +1 per payout, -1 per payout reversal. */
    private Integer payoutCount;
    private RoundStatus status;
    /** Partition key: UTC+8 date of the round's first event. */
    private LocalDate roundDate;
    private LocalDateTime firstEventAt;
    private LocalDateTime lastEventAt;
    private LocalDateTime settledAt;
    /** Player balance after the wallet txn that closed the round; null when closed without one (resolver). */
    private BigDecimal balanceAfter;
    private Integer resolveAttempts;
    /** Last outcome reported by the provider resolver (RoundResolutionView.outcome). */
    private String resolveOutcome;
    /** RoundSettledEvent acknowledged by Kafka. */
    private Boolean eventPublished;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
