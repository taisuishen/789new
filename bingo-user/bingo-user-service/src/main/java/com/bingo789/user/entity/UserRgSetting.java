package com.bingo789.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Responsible-gaming settings, one row per player (see deploy/sql/01_user.sql for column semantics).
 * Deposit limits must be read through {@code RgLimitRules.effective}: a pending increase whose effective time
 * has passed is already the effective limit, even before it is copied into the main column.
 */
@Getter
@Setter
@TableName("user_rg_setting")
public class UserRgSetting {

    /** rg_lock_state: no RG wallet lock. */
    public static final int LOCK_NONE = 0;
    /** rg_lock_state: BET_LOCKED requested, wallet has not confirmed yet. */
    public static final int LOCK_PENDING = 1;
    /** rg_lock_state: wallet confirmed BET_LOCKED. */
    public static final int LOCK_CONFIRMED = 2;

    @TableId(type = IdType.INPUT)
    private Long userId;
    private BigDecimal dailyDepositLimit;
    private BigDecimal weeklyDepositLimit;
    private BigDecimal monthlyDepositLimit;
    private Integer sessionLimitMinutes;
    private BigDecimal pendingDailyDepositLimit;
    private BigDecimal pendingWeeklyDepositLimit;
    private BigDecimal pendingMonthlyDepositLimit;
    private Instant pendingEffectiveAt;
    private Instant selfExcludedUntil;
    private Instant coolOffUntil;
    private Integer rgLockState;
    private Integer version;
    private Instant updatedAt;

    public boolean isSelfExcluded(Instant now) {
        return selfExcludedUntil != null && selfExcludedUntil.isAfter(now);
    }

    public boolean isCoolingOff(Instant now) {
        return coolOffUntil != null && coolOffUntil.isAfter(now);
    }
}
