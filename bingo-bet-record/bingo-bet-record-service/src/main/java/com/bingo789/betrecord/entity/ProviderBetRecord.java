package com.bingo789.betrecord.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A bet record as reported by the provider's bet-history API. Partitioned by bet_time; timestamps are UTC+8. */
@Getter
@Setter
@TableName("provider_bet_record")
public class ProviderBetRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String providerCode;
    private String providerBetId;
    private String roundId;
    private Long userId;
    /** Player's line when the record was first stored (provider data carries none); kept on re-pulls. */
    private Integer userLine;
    private String currency;
    private String gameCode;
    /** GameType name from the lobby catalogue when the record was first stored (OTHER if unknown); kept on re-pulls. */
    private String gameType;
    /** Catalogue name when the record was first stored (the game code if unknown); kept on re-pulls. */
    private String gameName;
    private BigDecimal betAmount;
    private BigDecimal payoutAmount;
    /** Provider status as normalized by game-integration. */
    private String status;
    private LocalDateTime betTime;
    private LocalDateTime settleTime;
    /** ProviderBetEvent acknowledged by Kafka. */
    private Boolean published;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
