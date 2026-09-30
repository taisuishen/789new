package com.bingo789.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.risk.domain.ReviewStatus;
import com.bingo789.risk.domain.RiskReviewTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RiskReviewTaskMapper extends BaseMapper<RiskReviewTask> {

    @Update("""
            UPDATE risk_review_task
               SET status = #{status}, operator = #{operator}, decision_reason = #{reason,jdbcType=VARCHAR},
                   decided_at = NOW(3), updated_at = NOW(3)
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int decide(@Param("id") long id, @Param("status") ReviewStatus status, @Param("operator") String operator,
               @Param("reason") String reason);
}
