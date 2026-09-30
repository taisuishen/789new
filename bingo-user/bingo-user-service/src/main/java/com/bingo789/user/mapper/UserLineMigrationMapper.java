package com.bingo789.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.user.entity.UserLineMigration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface UserLineMigrationMapper extends BaseMapper<UserLineMigration> {

    /** Players with a migration the wallet has not acknowledged yet, created before {@code before}. */
    @Select("""
            SELECT DISTINCT user_id FROM user_line_migration
             WHERE wallet_synced = 0 AND created_at < #{before} AND user_id > #{afterUserId}
             ORDER BY user_id
             LIMIT #{limit}
            """)
    List<Long> selectUnsyncedUsers(@Param("before") Instant before, @Param("afterUserId") long afterUserId,
                                   @Param("limit") int limit);

    /** The wallet applied {@code version}: every migration of the player up to it is synced. */
    @Update("""
            UPDATE user_line_migration SET wallet_synced = 1, wallet_synced_at = NOW(3)
             WHERE user_id = #{userId} AND line_version <= #{version} AND wallet_synced = 0
            """)
    int markWalletSynced(@Param("userId") long userId, @Param("version") int version);
}
