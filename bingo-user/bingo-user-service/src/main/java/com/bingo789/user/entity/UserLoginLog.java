package com.bingo789.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Successful logins; ip and device_id are indexed for multi-account detection by risk. */
@Getter
@Setter
@TableName("user_login_log")
public class UserLoginLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Integer userLine;
    private String ip;
    private String deviceId;
    private String userAgent;
    private String countryCode;
    private Instant createdAt;
}
