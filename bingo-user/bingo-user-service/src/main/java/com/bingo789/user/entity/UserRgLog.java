package com.bingo789.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Append-only audit trail of responsible-gaming changes, kept for the regulator. */
@Getter
@Setter
@TableName("user_rg_log")
public class UserRgLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Integer userLine;
    private String action;
    /** JSON. */
    private String detail;
    private Instant createdAt;
}
