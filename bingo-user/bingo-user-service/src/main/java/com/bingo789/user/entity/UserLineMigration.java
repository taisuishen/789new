package com.bingo789.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Append-only audit of one line migration; walletSynced drives the wallet copy retry. */
@Getter
@Setter
@TableName("user_line_migration")
public class UserLineMigration {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Integer fromLine;
    private Integer toLine;
    /** user_account.line_version after this migration. */
    private Integer lineVersion;
    private Long shadowUserId;
    private Long retiredShadowUserId;
    private String operatorId;
    private String reason;
    private Integer walletSynced;
    private Instant walletSyncedAt;
    private Instant createdAt;
}
