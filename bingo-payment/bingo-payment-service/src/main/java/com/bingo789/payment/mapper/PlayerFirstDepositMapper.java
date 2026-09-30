package com.bingo789.payment.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlayerFirstDepositMapper {

    /** @return 1 when this order is the player's first successful deposit, 0 otherwise */
    @Insert("INSERT IGNORE INTO player_first_deposit (user_id, order_no) VALUES (#{userId}, #{orderNo})")
    int insertIgnore(@Param("userId") long userId, @Param("orderNo") String orderNo);
}
