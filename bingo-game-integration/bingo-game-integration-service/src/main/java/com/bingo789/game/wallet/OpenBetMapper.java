package com.bingo789.game.wallet;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Status changes are conditional UPDATEs: a CLOSED row never opens again, whatever order the callbacks arrive in. */
@Mapper
public interface OpenBetMapper extends BaseMapper<OpenBetRow> {

    String COLUMNS = "id, provider_code, txn_id, round_id, user_id, currency, game_code, session_token, amount, status, "
            + "placed_at, updated_at";

    /** A retried stake keeps its existing row (and status). */
    @Insert("""
            INSERT IGNORE INTO open_bet (id, provider_code, txn_id, round_id, user_id, currency, game_code, session_token,
                                         amount, status, placed_at)
            VALUES (#{id}, #{providerCode}, #{txnId}, #{roundId}, #{userId}, #{currency}, #{gameCode}, #{sessionToken},
                    #{amount}, 'PENDING', #{placedAt})
            """)
    int insertPending(OpenBetRow row);

    @Update("""
            UPDATE open_bet SET status = 'OPEN', amount = #{amount}, updated_at = NOW(3)
             WHERE provider_code = #{providerCode} AND txn_id = #{txnId} AND status = 'PENDING'
            """)
    int markOpen(@Param("providerCode") String providerCode, @Param("txnId") String txnId, @Param("amount") BigDecimal amount);

    /** The wallet refused the stake: nothing was debited. */
    @Update("""
            UPDATE open_bet SET status = 'CLOSED', updated_at = NOW(3)
             WHERE provider_code = #{providerCode} AND txn_id = #{txnId} AND status = 'PENDING'
            """)
    int closePending(@Param("providerCode") String providerCode, @Param("txnId") String txnId);

    @Update("""
            UPDATE open_bet SET status = 'CLOSED', updated_at = NOW(3)
             WHERE provider_code = #{providerCode} AND txn_id = #{txnId} AND status <> 'CLOSED'
            """)
    int closeTxn(@Param("providerCode") String providerCode, @Param("txnId") String txnId);

    @Update("""
            UPDATE open_bet SET status = 'CLOSED', updated_at = NOW(3)
             WHERE provider_code = #{providerCode} AND round_id = #{roundId} AND status <> 'CLOSED'
            """)
    int closeRound(@Param("providerCode") String providerCode, @Param("roundId") String roundId);

    /** PENDING and OPEN stakes placed in [from, to), oldest first (idx_open). */
    @Select("SELECT " + COLUMNS + " FROM open_bet WHERE provider_code = #{providerCode} AND status IN ('PENDING', 'OPEN') "
            + "AND placed_at >= #{from} AND placed_at < #{to} ORDER BY placed_at, id LIMIT #{limit}")
    List<OpenBetRow> findOpen(@Param("providerCode") String providerCode, @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to, @Param("limit") int limit);

    /** The latest stake placed with this token, whatever its status (idx_token). */
    @Select("SELECT " + COLUMNS + " FROM open_bet WHERE session_token = #{token} AND provider_code = #{providerCode} "
            + "ORDER BY id DESC LIMIT 1")
    OpenBetRow findLatestByToken(@Param("providerCode") String providerCode, @Param("token") String token);

    @Delete("DELETE FROM open_bet WHERE status = 'CLOSED' AND updated_at < #{before} LIMIT #{limit}")
    int deleteClosedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
