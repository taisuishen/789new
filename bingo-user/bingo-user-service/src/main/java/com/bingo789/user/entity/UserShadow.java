package com.bingo789.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Link from a real player to the shadow account left in a line the player was moved out of. */
@Getter
@Setter
@TableName("user_shadow")
public class UserShadow {

    /** user_account.id of the SHADOW account. */
    @TableId(type = IdType.INPUT)
    private Long shadowUserId;
    /** The real player. */
    private Long userId;
    /** Line the shadow lives in. */
    private Integer userLine;
    private Instant createdAt;
}
