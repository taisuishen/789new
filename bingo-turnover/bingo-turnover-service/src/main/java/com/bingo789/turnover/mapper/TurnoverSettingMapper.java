package com.bingo789.turnover.mapper;

import com.bingo789.turnover.domain.TurnoverSetting;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Global datasource (ds00): a few rows, cached in memory by SettingService. */
@Mapper
public interface TurnoverSettingMapper {

    @Select("SELECT * FROM turnover_setting")
    List<TurnoverSetting> selectAll();

    @Select("SELECT * FROM turnover_setting WHERE user_line = #{userLine} AND currency = #{currency}")
    TurnoverSetting select(@Param("userLine") int userLine, @Param("currency") String currency);

    @Insert("""
            INSERT INTO turnover_setting (user_line, currency, clear_below_balance, complete_below_remaining,
                                          deposit_multiplier, updated_by)
            VALUES (#{s.userLine}, #{s.currency}, #{s.clearBelowBalance}, #{s.completeBelowRemaining},
                    #{s.depositMultiplier}, #{s.updatedBy}) AS new
            ON DUPLICATE KEY UPDATE clear_below_balance = new.clear_below_balance,
                complete_below_remaining = new.complete_below_remaining,
                deposit_multiplier = new.deposit_multiplier, updated_by = new.updated_by
            """)
    int upsert(@Param("s") TurnoverSetting setting);
}
