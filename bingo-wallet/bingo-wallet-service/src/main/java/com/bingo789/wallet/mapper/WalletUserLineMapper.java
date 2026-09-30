package com.bingo789.wallet.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WalletUserLineMapper {

    /**
     * Stores the line only when {@code version} is newer than the stored one (retries and reordered calls are
     * harmless). MySQL applies the assignments left to right: user_line is decided before version changes.
     */
    @Insert("""
            INSERT INTO wallet_user_line (user_id, user_line, version) VALUES (#{userId}, #{userLine}, #{version}) AS new
            ON DUPLICATE KEY UPDATE
                user_line = IF(new.version > wallet_user_line.version, new.user_line, wallet_user_line.user_line),
                version = GREATEST(wallet_user_line.version, new.version)
            """)
    int upsert(@Param("userId") long userId, @Param("userLine") int userLine, @Param("version") long version);
}
