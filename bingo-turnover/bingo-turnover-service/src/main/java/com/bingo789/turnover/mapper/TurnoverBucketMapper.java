package com.bingo789.turnover.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.turnover.domain.TurnoverBucket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Every statement names one player (or players of one shard), so it runs on a single shard database. */
@Mapper
public interface TurnoverBucketMapper extends BaseMapper<TurnoverBucket> {

    /** In consumption order: narrowest scope first, then oldest. */
    @Select("""
            SELECT * FROM turnover_bucket
             WHERE user_id = #{userId} AND status = 'ACTIVE'
             ORDER BY currency, scope_rank, created_at, id
            """)
    List<TurnoverBucket> selectActive(@Param("userId") long userId);

    /** Pre-filter of a Kafka batch: most players have no open requirement and need no transaction at all. */
    @Select("""
            <script>
            SELECT DISTINCT user_id FROM turnover_bucket
             WHERE status = 'ACTIVE' AND user_id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectUsersWithActive(@Param("userIds") Collection<Long> userIds);

    @Select("SELECT * FROM turnover_bucket WHERE user_id = #{userId} AND source_type = #{sourceType} AND source_no = #{sourceNo}")
    TurnoverBucket selectBySource(@Param("userId") long userId, @Param("sourceType") String sourceType,
                                  @Param("sourceNo") String sourceNo);

    /** Optimistic: 0 rows when the bucket changed (or closed) since it was read. */
    @Update("""
            UPDATE turnover_bucket
               SET achieved_amount = #{b.achievedAmount}, status = #{b.status}, close_reason = #{b.closeReason},
                   closed_by = #{b.closedBy}, closed_at = #{b.closedAt}, version = version + 1
             WHERE id = #{b.id} AND version = #{b.version} AND status = 'ACTIVE'
            """)
    int updateState(@Param("b") TurnoverBucket bucket);

    /** Closes ACTIVE buckets of one currency: one bucket when {@code bucketId} is set, else all of them. */
    @Update("""
            <script>
            UPDATE turnover_bucket
               SET status = #{status}, close_reason = #{closeReason}, closed_by = #{closedBy}, closed_at = #{closedAt},
                   version = version + 1
             WHERE user_id = #{userId} AND currency = #{currency} AND status = 'ACTIVE'
            <if test="bucketId != null"> AND id = #{bucketId}</if>
            </script>
            """)
    int closeActive(@Param("userId") long userId, @Param("currency") String currency, @Param("bucketId") Long bucketId,
                    @Param("status") String status, @Param("closeReason") String closeReason,
                    @Param("closedBy") String closedBy, @Param("closedAt") LocalDateTime closedAt);

    /** Back office; {@code lines} null = every line. */
    @Select("""
            <script>
            SELECT * FROM turnover_bucket WHERE user_id = #{userId}
            <if test="status != null"> AND status = #{status}</if>
            <if test="lines != null">
               AND user_line IN
               <foreach collection="lines" item="line" open="(" separator="," close=")">#{line}</foreach>
            </if>
             ORDER BY id DESC
             LIMIT 200
            </script>
            """)
    List<TurnoverBucket> selectByUser(@Param("userId") long userId, @Param("status") String status,
                                      @Param("lines") Collection<Integer> lines);

    @Select("""
            SELECT COALESCE(SUM(GREATEST(required_amount - achieved_amount, 0)), 0) FROM turnover_bucket
             WHERE user_id = #{userId} AND currency = #{currency} AND status = 'ACTIVE'
            """)
    BigDecimal sumRemaining(@Param("userId") long userId, @Param("currency") String currency);
}
