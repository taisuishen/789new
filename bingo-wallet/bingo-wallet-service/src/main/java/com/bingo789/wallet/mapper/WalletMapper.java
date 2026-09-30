package com.bingo789.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.wallet.domain.Wallet;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * Balance changes are single conditional UPDATEs: the WHERE clause is the business rule, and an
 * affected-row count of 0 means "refused". No SELECT ... FOR UPDATE, no read-modify-write.
 * <p>
 * {@code maxStatus} is the highest {@link com.bingo789.wallet.api.enums.WalletStatus} code that may still
 * be debited (1 = only ACTIVE, 2 = ACTIVE or BET_LOCKED, 3 = any).
 */
@Mapper
public interface WalletMapper extends BaseMapper<Wallet> {

    String COLUMNS = "id, user_id, currency, user_line, balance, frozen, status, version, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM wallet WHERE user_id = #{userId} AND currency = #{currency}")
    Wallet find(@Param("userId") long userId, @Param("currency") String currency);

    @Select("SELECT " + COLUMNS + " FROM wallet WHERE user_id = #{userId}")
    List<Wallet> findByUser(@Param("userId") long userId);

    /** @param shardNo logical shard of the user, kept on the row so a shard migration can select its users */
    /** A new wallet (e.g. a new currency) takes the player's line from wallet_user_line (missing = line 1). */
    @Insert("""
            INSERT IGNORE INTO wallet (id, user_id, currency, shard_no, user_line, balance, frozen, status, version)
            SELECT #{id}, #{userId}, #{currency}, #{shardNo},
                   COALESCE((SELECT l.user_line FROM wallet_user_line l WHERE l.user_id = #{userId}), 1), 0, 0, 1, 0
            """)
    int insertIgnore(@Param("id") long id, @Param("userId") long userId, @Param("currency") String currency,
                     @Param("shardNo") int shardNo);

    @Update("""
            UPDATE wallet SET balance = balance - #{amount}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND currency = #{currency} AND status <= #{maxStatus} AND balance >= #{amount}
            """)
    int debit(@Param("userId") long userId, @Param("currency") String currency,
              @Param("amount") BigDecimal amount, @Param("maxStatus") int maxStatus);

    /** Unconditional debit, only for provider-driven reversals when negative balances are allowed. */
    @Update("""
            UPDATE wallet SET balance = balance - #{amount}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND currency = #{currency}
            """)
    int forceDebit(@Param("userId") long userId, @Param("currency") String currency, @Param("amount") BigDecimal amount);

    /** Credits are accepted whatever the wallet status: money owed to the player is always paid. */
    @Update("""
            UPDATE wallet SET balance = balance + #{amount}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND currency = #{currency}
            """)
    int credit(@Param("userId") long userId, @Param("currency") String currency, @Param("amount") BigDecimal amount);

    /** Stake and win in one statement, guarded by the stake alone. */
    @Update("""
            UPDATE wallet SET balance = balance - #{betAmount} + #{payoutAmount}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND currency = #{currency} AND status <= #{maxStatus} AND balance >= #{betAmount}
            """)
    int debitThenCredit(@Param("userId") long userId, @Param("currency") String currency,
                        @Param("betAmount") BigDecimal betAmount, @Param("payoutAmount") BigDecimal payoutAmount,
                        @Param("maxStatus") int maxStatus);

    /** Takes the row lock without moving money, to serialize with concurrent operations of the same player. */
    @Update("UPDATE wallet SET version = version + 1 WHERE user_id = #{userId} AND currency = #{currency}")
    int lockRow(@Param("userId") long userId, @Param("currency") String currency);

    @Update("""
            UPDATE wallet SET balance = balance - #{amount}, frozen = frozen + #{amount}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND currency = #{currency} AND status <= #{maxStatus} AND balance >= #{amount}
            """)
    int freeze(@Param("userId") long userId, @Param("currency") String currency,
               @Param("amount") BigDecimal amount, @Param("maxStatus") int maxStatus);

    /** Withdrawal paid out: the frozen amount leaves the wallet. */
    @Update("""
            UPDATE wallet SET frozen = frozen - #{amount}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND currency = #{currency} AND frozen >= #{amount}
            """)
    int releaseFrozen(@Param("userId") long userId, @Param("currency") String currency, @Param("amount") BigDecimal amount);

    /** Withdrawal rejected or failed: the frozen amount returns to the balance. */
    @Update("""
            UPDATE wallet SET balance = balance + #{amount}, frozen = frozen - #{amount}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND currency = #{currency} AND frozen >= #{amount}
            """)
    int unfreeze(@Param("userId") long userId, @Param("currency") String currency, @Param("amount") BigDecimal amount);

    @Update("""
            UPDATE wallet SET status = #{status}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND currency = #{currency}
            """)
    int updateStatus(@Param("userId") long userId, @Param("currency") String currency, @Param("status") int status);

    /** Row locks on every wallet of the user, so concurrent lock changes for one player serialize. */
    @Update("UPDATE wallet SET version = version + 1 WHERE user_id = #{userId}")
    int lockAllRows(@Param("userId") long userId);

    /** Copies the authoritative line onto every currency row of the player (balance and version untouched). */
    @Update("""
            UPDATE wallet SET user_line = (SELECT l.user_line FROM wallet_user_line l WHERE l.user_id = #{userId})
             WHERE user_id = #{userId}
            """)
    int copyUserLine(@Param("userId") long userId);
}
