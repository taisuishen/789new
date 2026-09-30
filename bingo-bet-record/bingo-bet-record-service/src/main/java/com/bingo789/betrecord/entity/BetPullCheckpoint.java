package com.bingo789.betrecord.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("bet_pull_checkpoint")
public class BetPullCheckpoint {

    @TableId(value = "provider_code", type = IdType.INPUT)
    private String providerCode;
    /** End (exclusive, UTC+8) of the last window whose pages were all stored and published. */
    private LocalDateTime windowEnd;
    private LocalDateTime updatedAt;
}
