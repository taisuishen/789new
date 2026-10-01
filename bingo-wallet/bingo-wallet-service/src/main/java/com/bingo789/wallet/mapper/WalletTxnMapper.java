package com.bingo789.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.wallet.domain.WalletTxn;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Every query belongs to one user and runs inside that user's shard scope (ShardTemplate.forUser), so it
 * touches a single shard database; user_id stays in every WHERE clause for the indexes.
 */
@Mapper
public interface WalletTxnMapper extends BaseMapper<WalletTxn> {

    String COLUMNS = "id, user_id, user_line, currency, txn_type, direction, amount, balance_before, balance_after, provider_code, "
            + "provider_txn_id, ext_txn_id, round_id, game_code, ref_txn_id, round_closed, status, remark, created_at";

    /** Lookup by the idempotency key (uk_idempotency). */
    @Select("SELECT " + COLUMNS + " FROM wallet_txn WHERE user_id = #{userId} AND provider_code = #{providerCode} "
            + "AND provider_txn_id = #{providerTxnId} AND txn_type = #{txnType}")
    WalletTxn findByKey(@Param("userId") long userId, @Param("providerCode") String providerCode,
                        @Param("providerTxnId") String providerTxnId, @Param("txnType") String txnType);

    /** Normal (non-tombstone) bets of a round, oldest first. */
    @Select("SELECT " + COLUMNS + " FROM wallet_txn WHERE user_id = #{userId} AND provider_code = #{providerCode} "
            + "AND round_id = #{roundId} AND txn_type = 'BET' AND status = 1 ORDER BY id")
    List<WalletTxn> findBetsInRound(@Param("userId") long userId, @Param("providerCode") String providerCode,
                                    @Param("roundId") String roundId);

    /**
     * Live (not reversed) PAYOUT rows of the round that settle this bet: the ones referencing it, or referencing no
     * specific bet. Stakeless payout types (free spins, jackpots, promotions) do not settle a stake.
     */
    @Select("SELECT COUNT(*) FROM wallet_txn p WHERE p.user_id = #{userId} AND p.provider_code = #{providerCode} "
            + "AND p.round_id = #{roundId} AND p.txn_type = 'PAYOUT' AND p.status = 1 "
            + "AND (p.ref_txn_id IS NULL OR p.ref_txn_id = #{betTxnId}) "
            + "AND NOT EXISTS (SELECT 1 FROM wallet_txn r WHERE r.provider_code = p.provider_code "
            + "AND r.provider_txn_id = p.provider_txn_id AND r.txn_type = 'PAYOUT_REVERSAL' AND r.user_id = p.user_id)")
    int countLivePayouts(@Param("userId") long userId, @Param("providerCode") String providerCode,
                         @Param("roundId") String roundId, @Param("betTxnId") String betTxnId);

    /** A player's history without tombstones, newest first (idx_user_created); run it inside ReplicaRoute. */
    @Select("<script>SELECT " + COLUMNS + " FROM wallet_txn"
            + " WHERE user_id = #{userId} AND status = 1 AND created_at &gt;= #{from} AND created_at &lt; #{to}"
            + "<if test='currency != null'> AND currency = #{currency}</if>"
            + " ORDER BY created_at DESC, id DESC LIMIT #{offset}, #{limit}</script>")
    List<WalletTxn> findHistory(@Param("userId") long userId, @Param("currency") String currency,
                                @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                @Param("offset") int offset, @Param("limit") int limit);

    /**
     * Retention: removes up to {@code limit} of the oldest rows below {@code beforeId} (snowflake ids are time
     * ordered, so this walks the clustered index from its start). The CDC job ignores deletes.
     */
    @Delete("DELETE FROM wallet_txn WHERE id < #{beforeId} ORDER BY id LIMIT #{limit}")
    int deleteOlderThan(@Param("beforeId") long beforeId, @Param("limit") int limit);
}
