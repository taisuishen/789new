package com.bingo789.betrecord.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.betrecord.entity.GameRound;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Every statement filters on round_date (or scans an index that is small by nature) to keep partition pruning. */
@Mapper
public interface GameRoundMapper extends BaseMapper<GameRound> {

    /** Round of one player, searched in [fromDate, toDate] and locked for the rest of the transaction. */
    @Select("""
            SELECT * FROM game_round
             WHERE provider_code = #{providerCode} AND round_id = #{roundId} AND user_id = #{userId}
               AND round_date BETWEEN #{fromDate} AND #{toDate}
             ORDER BY round_date DESC
             LIMIT 1
               FOR UPDATE
            """)
    GameRound findForUpdate(@Param("providerCode") String providerCode, @Param("roundId") String roundId,
                            @Param("userId") long userId, @Param("fromDate") LocalDate fromDate,
                            @Param("toDate") LocalDate toDate);

    @Select("SELECT * FROM game_round WHERE id = #{id} AND round_date = #{roundDate} FOR UPDATE")
    GameRound lockById(@Param("id") long id, @Param("roundDate") LocalDate roundDate);

    @Update("""
            UPDATE game_round
               SET bet_amount = bet_amount + #{betDelta},
                   payout_amount = payout_amount + #{payoutDelta},
                   bet_count = bet_count + #{betCountDelta},
                   payout_count = payout_count + #{payoutCountDelta},
                   last_event_at = GREATEST(last_event_at, #{eventAt})
             WHERE id = #{id} AND round_date = #{roundDate}
            """)
    int applyDelta(@Param("id") long id, @Param("roundDate") LocalDate roundDate,
                   @Param("betDelta") BigDecimal betDelta, @Param("payoutDelta") BigDecimal payoutDelta,
                   @Param("betCountDelta") int betCountDelta, @Param("payoutCountDelta") int payoutCountDelta,
                   @Param("eventAt") LocalDateTime eventAt);

    /**
     * OPEN -> SETTLED / CANCELLED (revision 1), at most once. balanceAfter is stored so that a re-sent
     * RoundSettledEvent carries the same value as the first send.
     */
    @Update("""
            UPDATE game_round SET status = #{status}, settled_at = #{settledAt}, balance_after = #{balanceAfter}, revision = 1
             WHERE id = #{id} AND round_date = #{roundDate} AND status = 'OPEN'
            """)
    int close(@Param("id") long id, @Param("roundDate") LocalDate roundDate, @Param("status") String status,
              @Param("settledAt") LocalDateTime settledAt, @Param("balanceAfter") BigDecimal balanceAfter);

    /**
     * A closed round changed (late rollback / payout / adjustment): next revision, to be published again.
     * settled_at stays: it is part of the round's identity downstream (StarRocks primary key, business day).
     */
    @Update("""
            UPDATE game_round SET status = #{status}, revision = revision + 1, event_published = 0
             WHERE id = #{id} AND round_date = #{roundDate} AND status <> 'OPEN'
            """)
    int revise(@Param("id") long id, @Param("roundDate") LocalDate roundDate, @Param("status") String status);

    /**
     * Keyset scan. OPEN rounds are a small fraction of the table, so the index is forced: with ORDER BY id LIMIT n
     * the optimizer may otherwise walk the primary key through the whole table.
     */
    @Select("""
            SELECT * FROM game_round FORCE INDEX (idx_status_last_event)
             WHERE status = 'OPEN' AND last_event_at < #{before} AND id > #{afterId}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<GameRound> findOverdue(@Param("before") LocalDateTime before, @Param("afterId") long afterId,
                                @Param("limit") int limit);

    @Update("""
            UPDATE game_round SET resolve_attempts = resolve_attempts + 1, resolve_outcome = #{outcome}
             WHERE id = #{id} AND round_date = #{roundDate} AND status = 'OPEN'
            """)
    int recordResolveAttempt(@Param("id") long id, @Param("roundDate") LocalDate roundDate, @Param("outcome") String outcome);

    /** Terminal rounds whose RoundSettledEvent was never acknowledged (crash or broker outage after commit). */
    @Select("""
            SELECT * FROM game_round FORCE INDEX (idx_unpublished)
             WHERE event_published = 0 AND settled_at < #{before} AND id > #{afterId}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<GameRound> findUnpublished(@Param("before") LocalDateTime before, @Param("afterId") long afterId,
                                    @Param("limit") int limit);

    /** Only the acknowledged revision: a newer revision committed meanwhile stays unpublished and is sent too. */
    @Update("""
            UPDATE game_round SET event_published = 1
             WHERE id = #{id} AND round_date = #{roundDate} AND revision = #{revision}
            """)
    int markPublished(@Param("id") long id, @Param("roundDate") LocalDate roundDate, @Param("revision") int revision);
}
