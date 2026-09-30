package com.bingo789.betrecord.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.betrecord.entity.ProviderBetRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Lookups carry a bet_time range so they prune to the partitions of one pull window. */
@Mapper
public interface ProviderBetRecordMapper extends BaseMapper<ProviderBetRecord> {

    /**
     * Records re-pulled through the window overlap update the provider-mutable fields only; the snapshots taken
     * when the record was first stored (user_line, game_type, game_name) are kept.
     */
    @Insert("""
            <script>
            INSERT INTO provider_bet_record (id, provider_code, provider_bet_id, round_id, user_id, user_line, currency,
                                             game_code, game_type, game_name, bet_amount, payout_amount, status,
                                             bet_time, settle_time, published)
            VALUES
            <foreach collection="rows" item="r" separator=",">
              (#{r.id}, #{r.providerCode}, #{r.providerBetId}, #{r.roundId}, #{r.userId}, #{r.userLine}, #{r.currency},
               #{r.gameCode}, #{r.gameType}, #{r.gameName}, #{r.betAmount}, #{r.payoutAmount}, #{r.status},
               #{r.betTime}, #{r.settleTime}, 0)
            </foreach>
            AS new
            ON DUPLICATE KEY UPDATE payout_amount = new.payout_amount, status = new.status, settle_time = new.settle_time
            </script>
            """)
    int upsertBatch(@Param("rows") List<ProviderBetRecord> rows);

    /** Already stored records of a page: provider_bet_id, user_line and published only. */
    @Select("""
            <script>
            SELECT provider_bet_id, user_line, published FROM provider_bet_record
             WHERE provider_code = #{providerCode} AND bet_time BETWEEN #{from} AND #{to}
               AND provider_bet_id IN
               <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ProviderBetRecord> findStored(@Param("providerCode") String providerCode, @Param("ids") Collection<String> ids,
                                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Update("""
            <script>
            UPDATE provider_bet_record SET published = 1
             WHERE provider_code = #{providerCode} AND bet_time BETWEEN #{from} AND #{to}
               AND provider_bet_id IN
               <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markPublished(@Param("providerCode") String providerCode, @Param("ids") Collection<String> ids,
                      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
