package com.bingo789.lobby.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("game_category")
public class GameCategory {

    @TableId(value = "code", type = IdType.INPUT)
    private String code;
    private String name;
    private Integer sort;
}
