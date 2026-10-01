package com.bingo789.promotion.mapper;

import com.bingo789.promotion.domain.PromotionRoundApplied;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Per-round dedupe for the round-settled stream, with the revision and valid bet already counted. */
@Mapper
public interface PromotionRoundAppliedMapper {

    @Select("""
            <script>
            SELECT round_key, revision, valid_bet FROM promotion_round_applied WHERE round_key IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
            </script>
            """)
    List<PromotionRoundApplied> selectExisting(@Param("keys") Collection<String> keys);

    /** Plain INSERT on purpose: a concurrent duplicate fails the transaction and the batch is retried. */
    @Insert("""
            <script>
            INSERT INTO promotion_round_applied (round_key, revision, valid_bet) VALUES
            <foreach collection="rows" item="r" separator=",">(#{r.roundKey}, #{r.revision}, #{r.validBet})</foreach>
            </script>
            """)
    int insertBatch(@Param("rows") Collection<PromotionRoundApplied> rows);

    /** Optimistic on the revision read: 0 rows when another consumer applied a revision meanwhile. */
    @Update("""
            UPDATE promotion_round_applied SET revision = #{revision}, valid_bet = #{validBet}
             WHERE round_key = #{roundKey} AND revision = #{readRevision}
            """)
    int updateRevision(@Param("roundKey") String roundKey, @Param("revision") int revision,
                       @Param("validBet") BigDecimal validBet, @Param("readRevision") int readRevision);

    @Delete("DELETE FROM promotion_round_applied WHERE created_at < #{before} LIMIT #{limit}")
    int deleteOlderThan(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
