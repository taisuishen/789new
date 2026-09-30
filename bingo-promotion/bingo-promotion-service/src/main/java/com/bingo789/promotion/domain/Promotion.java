package com.bingo789.promotion.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A promotion ("活动"). The JSON columns are kept as their JSON text; {@link PromotionEntry} is the parsed form.
 * Versioned updates go through {@code PromotionMapper}, never {@code updateById}.
 */
@Getter
@Setter
@TableName("promotion")
public class Promotion {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    /** {@link PromotionType} name; a String so rows of types unknown to this version still load. */
    private String promoType;
    /** JSON array of the lines whose players see the promotion, e.g. [1,2]. */
    private String userLines;
    private LocalDateTime startTime;
    /** Exclusive. */
    private LocalDateTime endTime;
    private PromotionStatus status;
    /** Higher first. */
    private Integer sort;
    /** JSON object; only its "display" object is player-facing. */
    private String configJson;
    private Integer version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
