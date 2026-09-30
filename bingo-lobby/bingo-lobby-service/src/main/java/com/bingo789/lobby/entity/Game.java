package com.bingo789.lobby.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Provider-owned fields are refreshed by the catalogue sync; status and sort are operator-owned. */
@Getter
@Setter
@TableName("game")
public class Game {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String providerCode;
    private String gameCode;
    private String name;
    private String category;
    /** GameType name; operator-owned after insert. */
    private String gameType;
    /** Percent, e.g. 96.500 (certified value). */
    private BigDecimal theoreticalRtp;
    private String thumbnailUrl;
    private GameStatus status;
    private Boolean mobileSupported;
    private Boolean desktopSupported;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
