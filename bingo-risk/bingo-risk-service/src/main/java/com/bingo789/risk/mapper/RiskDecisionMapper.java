package com.bingo789.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import com.bingo789.risk.domain.RiskDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RiskDecisionMapper extends BaseMapper<RiskDecision> {

    default RiskDecision selectByOrderNo(String orderNo) {
        return selectOne(Wrappers.<RiskDecision>lambdaQuery().eq(RiskDecision::getOrderNo, orderNo));
    }

    /** Other withdrawal requests of the player that risk has evaluated (any outcome). */
    @Select("SELECT COUNT(*) FROM risk_decision WHERE user_id = #{userId} AND order_no <> #{excludeOrderNo}")
    long countOthers(@Param("userId") long userId, @Param("excludeOrderNo") String excludeOrderNo);

    @Select("""
            SELECT COUNT(*) FROM risk_decision
             WHERE user_id = #{userId} AND order_no <> #{excludeOrderNo} AND created_at >= #{since}
            """)
    long countOthersSince(@Param("userId") long userId, @Param("excludeOrderNo") String excludeOrderNo,
                          @Param("since") LocalDateTime since);

    /** Written once: the first final decision wins. */
    @Update("""
            UPDATE risk_decision
               SET final_decision = #{decision}, final_reason = #{reason,jdbcType=VARCHAR}, auditor = #{auditor}, updated_at = NOW(3)
             WHERE order_no = #{orderNo} AND final_decision IS NULL
            """)
    int setFinalDecision(@Param("orderNo") String orderNo, @Param("decision") WithdrawAuditCommand.Decision decision,
                         @Param("reason") String reason, @Param("auditor") String auditor);

    @Update("UPDATE risk_decision SET delivered = 1, delivered_at = NOW(3), updated_at = NOW(3) WHERE id = #{id} AND delivered = 0")
    int markDelivered(@Param("id") long id);

    /** Final decisions payment has not acknowledged yet, keyset-paged by id. */
    default List<RiskDecision> selectUndelivered(LocalDateTime updatedBefore, long afterId, int limit) {
        return selectList(Wrappers.<RiskDecision>lambdaQuery()
                .eq(RiskDecision::getDelivered, false)
                .isNotNull(RiskDecision::getFinalDecision)
                .lt(RiskDecision::getUpdatedAt, updatedBefore)
                .gt(RiskDecision::getId, afterId)
                .orderByAsc(RiskDecision::getId)
                .last("LIMIT " + limit));
    }
}
