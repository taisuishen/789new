package com.bingo789.turnover.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One 稽核记录: an append-only change of a bucket; WAGER rows with (roundKey, seq = 0) are the exactly-once marker. */
@Getter
@Setter
@TableName("turnover_record")
public class TurnoverRecord {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Integer userLine;
    private Long bucketId;
    private RecordType recordType;
    private String currency;
    private BigDecimal amount;
    private BigDecimal achievedAfter;
    private BigDecimal remainingAfter;
    private BucketStatus statusAfter;
    private String reason;
    private String operator;
    private String roundKey;
    /** WAGER only: the round revision that produced the row (1 = first settlement). */
    private Integer roundRevision;
    private Integer seq;
    private String providerCode;
    private String gameCode;
    private String gameType;
    private LocalDate recordDate;
    private LocalDateTime createdAt;
}
