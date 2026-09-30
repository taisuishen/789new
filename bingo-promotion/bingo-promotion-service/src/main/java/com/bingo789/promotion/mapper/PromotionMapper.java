package com.bingo789.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.PromotionStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PromotionMapper extends BaseMapper<Promotion> {

    /** Catalog load: every ONLINE promotion that has not ended yet (idx_status_end). */
    default List<Promotion> selectOnlineNotEnded(LocalDateTime now) {
        return selectList(Wrappers.<Promotion>lambdaQuery()
                .eq(Promotion::getStatus, PromotionStatus.ONLINE)
                .gt(Promotion::getEndTime, now));
    }

    /** ONLINE promotions of {@code type} active at {@code at}, highest priority first (sort desc, id desc). */
    default List<Promotion> selectOnlineActiveAt(String type, LocalDateTime at) {
        return selectList(Wrappers.<Promotion>lambdaQuery()
                .eq(Promotion::getStatus, PromotionStatus.ONLINE)
                .eq(Promotion::getPromoType, type)
                .le(Promotion::getStartTime, at)
                .gt(Promotion::getEndTime, at)
                .orderByDesc(Promotion::getSort)
                .orderByDesc(Promotion::getId));
    }

    /** ONLINE promotions of {@code type} active at any time in [from, to), highest priority first. */
    default List<Promotion> selectOnlineOverlapping(String type, LocalDateTime from, LocalDateTime to) {
        return selectList(Wrappers.<Promotion>lambdaQuery()
                .eq(Promotion::getStatus, PromotionStatus.ONLINE)
                .eq(Promotion::getPromoType, type)
                .lt(Promotion::getStartTime, to)
                .gt(Promotion::getEndTime, from)
                .orderByDesc(Promotion::getSort)
                .orderByDesc(Promotion::getId));
    }

    /** Optimistic update of the editable fields; 0 rows = unknown id or stale {@code expectedVersion}. */
    @Update("""
            UPDATE promotion SET name = #{p.name}, user_lines = #{p.userLines}, start_time = #{p.startTime},
                   end_time = #{p.endTime}, sort = #{p.sort}, config_json = #{p.configJson}, updated_by = #{p.updatedBy},
                   version = version + 1, updated_at = NOW(3)
             WHERE id = #{p.id} AND version = #{expectedVersion}
            """)
    int updateVersioned(@Param("p") Promotion promotion, @Param("expectedVersion") int expectedVersion);

    /** Optimistic status change; 0 rows = unknown id or stale {@code expectedVersion}. */
    @Update("""
            UPDATE promotion SET status = #{status}, updated_by = #{updatedBy}, version = version + 1, updated_at = NOW(3)
             WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateStatus(@Param("id") long id, @Param("status") PromotionStatus status, @Param("updatedBy") String updatedBy,
                     @Param("expectedVersion") int expectedVersion);
}
