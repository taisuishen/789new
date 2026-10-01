package com.bingo789.turnover.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.turnover.domain.TurnoverRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface TurnoverRecordMapper extends BaseMapper<TurnoverRecord> {

    /** Rounds already applied (a seq-0 WAGER row of any revision exists). */
    @Select("""
            <script>
            SELECT round_key FROM turnover_record
             WHERE seq = 0 AND round_key IN
            <foreach collection="roundKeys" item="k" open="(" separator="," close=")">#{k}</foreach>
            </script>
            """)
    List<String> selectAppliedRoundKeys(@Param("roundKeys") Collection<String> roundKeys);

    /** A duplicate (round, seq) fails the whole statement, which rolls the player's batch back for a clean retry. */
    @Insert("""
            <script>
            INSERT INTO turnover_record (id, user_id, user_line, bucket_id, record_type, currency, amount, achieved_after,
                                         remaining_after, status_after, reason, operator, round_key, round_revision, seq,
                                         provider_code, game_code, game_type, record_date, created_at)
            VALUES
            <foreach collection="records" item="r" separator=",">
              (#{r.id}, #{r.userId}, #{r.userLine}, #{r.bucketId}, #{r.recordType}, #{r.currency}, #{r.amount},
               #{r.achievedAfter}, #{r.remainingAfter}, #{r.statusAfter}, #{r.reason}, #{r.operator}, #{r.roundKey},
               #{r.roundRevision}, #{r.seq}, #{r.providerCode}, #{r.gameCode}, #{r.gameType}, #{r.recordDate}, NOW(3))
            </foreach>
            </script>
            """)
    int insertBatch(@Param("records") List<TurnoverRecord> records);

    /** Every WAGER row of one round (all revisions), oldest first. */
    @Select("""
            SELECT * FROM turnover_record
             WHERE round_key = #{roundKey} AND record_type = 'WAGER'
             ORDER BY round_revision, seq
            """)
    List<TurnoverRecord> selectRoundWagers(@Param("roundKey") String roundKey);

    /** Newest first; {@code bucketId} null = all of the player's buckets, {@code lines} null = every line. */
    @Select("""
            <script>
            SELECT * FROM turnover_record WHERE user_id = #{userId}
            <if test="bucketId != null"> AND bucket_id = #{bucketId}</if>
            <if test="lines != null">
               AND user_line IN
               <foreach collection="lines" item="line" open="(" separator="," close=")">#{line}</foreach>
            </if>
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit}
            </script>
            """)
    List<TurnoverRecord> selectByUser(@Param("userId") long userId, @Param("bucketId") Long bucketId,
                                      @Param("lines") Collection<Integer> lines, @Param("limit") int limit);
}
