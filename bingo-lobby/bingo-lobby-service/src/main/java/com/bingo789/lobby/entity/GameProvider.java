package com.bingo789.lobby.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bingo789.lobby.api.enums.ProviderStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("game_provider")
public class GameProvider {

    @TableId(value = "code", type = IdType.INPUT)
    private String code;
    private String name;
    private WalletMode walletMode;
    private ProviderStatus status;
    private String statusReason;
    private Integer sort;
    private LocalDateTime updatedAt;
}
