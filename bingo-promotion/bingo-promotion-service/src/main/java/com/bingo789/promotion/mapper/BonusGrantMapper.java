package com.bingo789.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.promotion.domain.BonusGrant;
import com.bingo789.promotion.domain.BonusStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BonusGrantMapper extends BaseMapper<BonusGrant> {

    default BonusGrant selectByBizNo(String bizNo) {
        return selectOne(Wrappers.<BonusGrant>lambdaQuery().eq(BonusGrant::getBizNo, bizNo));
    }

    default List<BonusGrant> selectPendingBefore(LocalDateTime createdBefore, long afterId, int limit) {
        return selectList(Wrappers.<BonusGrant>lambdaQuery()
                .eq(BonusGrant::getStatus, BonusStatus.PENDING)
                .lt(BonusGrant::getCreatedAt, createdBefore)
                .gt(BonusGrant::getId, afterId)
                .orderByAsc(BonusGrant::getId)
                .last("LIMIT " + limit));
    }

    @Update("UPDATE bonus_grant SET status = 'PAID', paid_at = NOW(3), updated_at = NOW(3) WHERE id = #{id} AND status = 'PENDING'")
    int markPaid(@Param("id") long id);

    @Update("""
            UPDATE bonus_grant SET status = 'FAILED', fail_reason = #{reason,jdbcType=VARCHAR}, updated_at = NOW(3)
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int markFailed(@Param("id") long id, @Param("reason") String reason);
}
