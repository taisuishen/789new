package com.bingo789.promotion.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** Per-round dedupe for the round-settled stream (see 08_promotion.sql for the retention TODO). */
@Mapper
public interface PromotionRoundAppliedMapper {

    @Select("""
            <script>
            SELECT round_key FROM promotion_round_applied WHERE round_key IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
            </script>
            """)
    List<String> selectExisting(@Param("keys") Collection<String> keys);

    /** Plain INSERT on purpose: a concurrent duplicate fails the transaction and the batch is retried. */
    @Insert("""
            <script>
            INSERT INTO promotion_round_applied (round_key) VALUES
            <foreach collection="keys" item="k" separator=",">(#{k})</foreach>
            </script>
            """)
    int insertBatch(@Param("keys") Collection<String> keys);
}
