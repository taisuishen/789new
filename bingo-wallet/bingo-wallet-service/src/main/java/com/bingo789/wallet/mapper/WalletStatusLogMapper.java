package com.bingo789.wallet.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Audit trail of wallet status changes (self-exclusion, AML holds); required for regulatory review. */
@Mapper
public interface WalletStatusLogMapper {

    @Insert("""
            INSERT INTO wallet_status_log (user_id, user_line, currency, status, reason, created_at)
            VALUES (#{userId}, COALESCE((SELECT l.user_line FROM wallet_user_line l WHERE l.user_id = #{userId}), 1),
                    #{currency}, #{status}, #{reason}, NOW(3))
            """)
    int insert(@Param("userId") long userId, @Param("currency") String currency,
               @Param("status") int status, @Param("reason") String reason);
}
