package com.bingo789.promotion.mapper;

import com.bingo789.promotion.domain.ValidBetDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ValidBetDailyMapper {

    /**
     * Batch pre-aggregated upsert (MySQL 8 row alias). Callers pass rows sorted by key for a stable lock order.
     * user_line takes the latest round's line: if a player migrates mid-day, the day's rows end up on the later line.
     */
    @Insert("""
            <script>
            INSERT INTO valid_bet_daily (stat_date, user_id, user_line, currency, provider_code, valid_bet, round_count) VALUES
            <foreach collection="rows" item="r" separator=",">
              (#{r.statDate}, #{r.userId}, #{r.userLine}, #{r.currency}, #{r.providerCode}, #{r.validBet}, #{r.roundCount})
            </foreach>
            AS new
            ON DUPLICATE KEY UPDATE valid_bet = valid_bet_daily.valid_bet + new.valid_bet,
                                    round_count = valid_bet_daily.round_count + new.round_count,
                                    user_line = new.user_line
            </script>
            """)
    int upsertBatch(@Param("rows") List<ValidBetDaily> rows);

    /** Keyset-paged player ids with valid bet on {@code statDate}. */
    @Select("""
            SELECT DISTINCT user_id FROM valid_bet_daily
             WHERE stat_date = #{statDate} AND user_id > #{afterUserId}
             ORDER BY user_id LIMIT #{limit}
            """)
    List<Long> selectUserIds(@Param("statDate") LocalDate statDate, @Param("afterUserId") long afterUserId,
                             @Param("limit") int limit);

    @Select("""
            <script>
            SELECT stat_date, user_id, user_line, currency, provider_code, valid_bet, round_count, updated_at FROM valid_bet_daily
             WHERE stat_date = #{statDate} AND user_id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ValidBetDaily> selectByUsers(@Param("statDate") LocalDate statDate, @Param("userIds") List<Long> userIds);
}
