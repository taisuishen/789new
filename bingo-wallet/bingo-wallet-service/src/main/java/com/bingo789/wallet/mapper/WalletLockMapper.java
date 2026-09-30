package com.bingo789.wallet.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Locks keyed by reason (SELF_EXCLUSION, COOL_OFF, KYC, AML_HOLD ...). The wallet status is always the strictest
 * active lock, so releasing one reason never lifts a lock another team placed for a different reason.
 * currency '*' applies to every wallet of the user, including wallets opened later.
 */
@Mapper
public interface WalletLockMapper {

    String ALL_CURRENCIES = "*";

    @Insert("""
            INSERT INTO wallet_lock (user_id, currency, reason, status, created_at, updated_at)
            VALUES (#{userId}, #{currency}, #{reason}, #{status}, NOW(3), NOW(3)) AS new
            ON DUPLICATE KEY UPDATE status = new.status, updated_at = new.updated_at
            """)
    int upsert(@Param("userId") long userId, @Param("currency") String currency,
               @Param("reason") String reason, @Param("status") int status);

    @Delete("DELETE FROM wallet_lock WHERE user_id = #{userId} AND currency = #{currency} AND reason = #{reason}")
    int delete(@Param("userId") long userId, @Param("currency") String currency, @Param("reason") String reason);

    /** Strictest lock that applies to one wallet; null when there is none. */
    @Select("SELECT MAX(status) FROM wallet_lock WHERE user_id = #{userId} AND currency IN (#{currency}, '*')")
    Integer strictest(@Param("userId") long userId, @Param("currency") String currency);
}
