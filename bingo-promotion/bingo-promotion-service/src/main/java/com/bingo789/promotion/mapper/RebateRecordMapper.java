package com.bingo789.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.promotion.domain.RebateRecord;
import com.bingo789.promotion.domain.RebateStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface RebateRecordMapper extends BaseMapper<RebateRecord> {

    /** Re-runs are idempotent: UNIQUE(stat_date, user_id, currency) keeps the first computed row. */
    @Insert("""
            <script>
            INSERT IGNORE INTO rebate_record (id, stat_date, user_id, user_line, currency, valid_bet, amount, promotion_id,
                                              turnover_multiplier, turnover_scope, turnover_scope_value, status) VALUES
            <foreach collection="rows" item="r" separator=",">
              (#{r.id}, #{r.statDate}, #{r.userId}, #{r.userLine}, #{r.currency}, #{r.validBet}, #{r.amount}, #{r.promotionId},
               #{r.turnoverMultiplier}, #{r.turnoverScope,jdbcType=VARCHAR}, #{r.turnoverScopeValue,jdbcType=VARCHAR}, 'PENDING')
            </foreach>
            </script>
            """)
    int insertIgnoreBatch(@Param("rows") List<RebateRecord> rows);

    default List<RebateRecord> selectPending(long afterId, int limit) {
        return selectList(Wrappers.<RebateRecord>lambdaQuery()
                .eq(RebateRecord::getStatus, RebateStatus.PENDING)
                .gt(RebateRecord::getId, afterId)
                .orderByAsc(RebateRecord::getId)
                .last("LIMIT " + limit));
    }

    default List<RebateRecord> selectByUser(long userId, LocalDate from, LocalDate to) {
        return selectList(Wrappers.<RebateRecord>lambdaQuery()
                .eq(RebateRecord::getUserId, userId)
                .between(RebateRecord::getStatDate, from, to)
                .orderByDesc(RebateRecord::getStatDate));
    }

    @Update("UPDATE rebate_record SET status = 'PAID', paid_at = NOW(3), updated_at = NOW(3) WHERE id = #{id} AND status = 'PENDING'")
    int markPaid(@Param("id") long id);

    @Update("""
            UPDATE rebate_record SET status = 'FAILED', fail_reason = #{reason,jdbcType=VARCHAR}, updated_at = NOW(3)
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int markFailed(@Param("id") long id, @Param("reason") String reason);
}
