package com.bingo789.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.user.entity.UserRgSetting;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * rg_lock_state values: 0 none, 1 BET_LOCKED requested, 2 BET_LOCKED confirmed (see {@link UserRgSetting}).
 * {@code version} is bumped by every change to limits or restrictions, so the expiry job can detect a new
 * restriction that arrived while it was unlocking the wallet.
 */
@Mapper
public interface UserRgSettingMapper extends BaseMapper<UserRgSetting> {

    @Insert("INSERT INTO user_rg_setting (user_id) VALUES (#{userId}) ON DUPLICATE KEY UPDATE user_id = user_id")
    int insertIfAbsent(@Param("userId") long userId);

    /** Optimistic update of the limits; 0 rows means the row changed since it was read. */
    @Update("""
            UPDATE user_rg_setting
               SET daily_deposit_limit = #{s.dailyDepositLimit},
                   weekly_deposit_limit = #{s.weeklyDepositLimit},
                   monthly_deposit_limit = #{s.monthlyDepositLimit},
                   session_limit_minutes = #{s.sessionLimitMinutes},
                   pending_daily_deposit_limit = #{s.pendingDailyDepositLimit},
                   pending_weekly_deposit_limit = #{s.pendingWeeklyDepositLimit},
                   pending_monthly_deposit_limit = #{s.pendingMonthlyDepositLimit},
                   pending_effective_at = #{s.pendingEffectiveAt},
                   version = version + 1
             WHERE user_id = #{s.userId} AND version = #{s.version}
            """)
    int updateLimits(@Param("s") UserRgSetting setting);

    /** Extends, never shortens, a self-exclusion, and marks the wallet lock as requested. */
    @Update("""
            UPDATE user_rg_setting
               SET self_excluded_until = CASE WHEN self_excluded_until IS NULL OR self_excluded_until < #{until}
                                              THEN #{until} ELSE self_excluded_until END,
                   rg_lock_state = 1,
                   version = version + 1
             WHERE user_id = #{userId}
            """)
    int extendSelfExclusion(@Param("userId") long userId, @Param("until") Instant until);

    /** Extends, never shortens, a cool-off, and marks the wallet lock as requested. */
    @Update("""
            UPDATE user_rg_setting
               SET cool_off_until = CASE WHEN cool_off_until IS NULL OR cool_off_until < #{until}
                                         THEN #{until} ELSE cool_off_until END,
                   rg_lock_state = 1,
                   version = version + 1
             WHERE user_id = #{userId}
            """)
    int extendCoolOff(@Param("userId") long userId, @Param("until") Instant until);

    @Update("UPDATE user_rg_setting SET rg_lock_state = 2 WHERE user_id = #{userId} AND rg_lock_state = 1")
    int markWalletLocked(@Param("userId") long userId);

    /** Clears the lock only if nothing changed since the expiry job read the row. */
    @Update("""
            UPDATE user_rg_setting SET rg_lock_state = 0
             WHERE user_id = #{userId} AND version = #{version} AND rg_lock_state <> 0
            """)
    int clearLockState(@Param("userId") long userId, @Param("version") int version);

    /** Locked rows whose self-exclusion and cool-off have both ended; keyset-paged by user_id. */
    @Select("""
            SELECT * FROM user_rg_setting
             WHERE rg_lock_state IN (1, 2) AND user_id > #{afterUserId}
               AND (self_excluded_until IS NULL OR self_excluded_until <= #{now})
               AND (cool_off_until IS NULL OR cool_off_until <= #{now})
             ORDER BY user_id
             LIMIT #{limit}
            """)
    List<UserRgSetting> selectExpiredLocks(@Param("now") Instant now,
                                           @Param("afterUserId") long afterUserId,
                                           @Param("limit") int limit);

    @Select("""
            SELECT * FROM user_rg_setting
             WHERE rg_lock_state = 1 AND user_id > #{afterUserId}
             ORDER BY user_id
             LIMIT #{limit}
            """)
    List<UserRgSetting> selectPendingLocks(@Param("afterUserId") long afterUserId, @Param("limit") int limit);
}
