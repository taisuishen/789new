package com.bingo789.user.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.Money;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.user.UserErrorCode;
import com.bingo789.user.api.dto.RgLimitsView;
import com.bingo789.user.config.RgProperties;
import com.bingo789.user.entity.UserAccount;
import com.bingo789.user.entity.UserRgLog;
import com.bingo789.user.entity.UserRgSetting;
import com.bingo789.user.mapper.UserAccountMapper;
import com.bingo789.user.mapper.UserRgLogMapper;
import com.bingo789.user.mapper.UserRgSettingMapper;
import com.bingo789.user.web.dto.CoolOffRequest;
import com.bingo789.user.web.dto.RgSettingsResponse;
import com.bingo789.user.web.dto.SelfExclusionRequest;
import com.bingo789.user.web.dto.UpdateRgLimitsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responsible gaming: deposit / session limits, self-exclusion and cool-off.
 * <p>
 * A restriction is persisted first, then enforced: wallet BET_LOCKED (rejects bets of already-open game sessions)
 * and revocation of every session. The wallet lock is tracked in rg_lock_state so that a failed wallet call is
 * retried by rgWalletLockRetryJob and the lock is lifted by rgExpiryJob once all restrictions have ended.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RgService {

    /** Stored in self_excluded_until for a permanent self-exclusion. */
    public static final Instant PERMANENT = Instant.parse("9999-12-31T00:00:00Z");

    public enum JobOutcome { DONE, SKIPPED, FAILED }

    private final UserRgSettingMapper rgMapper;
    private final UserRgLogMapper rgLogMapper;
    private final UserAccountMapper accountMapper;
    private final PlayerStatusService playerStatusService;
    private final SessionService sessionService;
    private final WalletLockService walletLock;
    private final RgProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // ---------------------------------------------------------------- reads

    /** Effective limits, used by payment for its deposit check. Read from the primary: limit decreases are immediate. */
    public RgLimitsView effectiveLimits(long userId) {
        Instant now = clock.instant();
        UserRgSetting setting = MasterRoute.run(() -> rgMapper.selectById(userId));
        if (setting == null) {
            // registration creates the row, so this is either an unknown user or an account without limits
            BizException.check(accountMapper.selectById(userId) != null, UserErrorCode.USER_NOT_FOUND);
            return new RgLimitsView(userId, null, null, null, null, null, null);
        }
        RgLimitRules.Limits limits = RgLimitRules.effective(setting, now);
        return new RgLimitsView(userId, limits.daily(), limits.weekly(), limits.monthly(), setting.getSessionLimitMinutes(),
                activeUntil(setting.getSelfExcludedUntil(), now), activeUntil(setting.getCoolOffUntil(), now));
    }

    public RgSettingsResponse settings(long userId) {
        UserRgSetting setting = MasterRoute.run(() -> rgMapper.selectById(userId));
        return setting == null ? RgSettingsResponse.none() : toResponse(setting, clock.instant());
    }

    // ---------------------------------------------------------------- limits

    @Transactional
    public RgSettingsResponse updateLimits(long userId, UpdateRgLimitsRequest request) {
        Instant now = clock.instant();
        rgMapper.insertIfAbsent(userId);
        UserRgSetting setting = MasterRoute.run(() -> rgMapper.selectById(userId));
        // Matured increases are folded in first, so the comparison is against what the player can deposit today.
        RgLimitRules.Limits current = RgLimitRules.effective(setting, now);

        RgLimitRules.Decision daily = RgLimitRules.decide(current.daily(), current.pendingDaily(), normalize(request.dailyDepositLimit()));
        RgLimitRules.Decision weekly = RgLimitRules.decide(current.weekly(), current.pendingWeekly(), normalize(request.weeklyDepositLimit()));
        RgLimitRules.Decision monthly = RgLimitRules.decide(current.monthly(), current.pendingMonthly(), normalize(request.monthlyDepositLimit()));

        boolean newIncrease = daily.newIncrease() || weekly.newIncrease() || monthly.newIncrease();
        boolean anyPending = daily.pending() != null || weekly.pending() != null || monthly.pending() != null;
        // One effective time for all pending increases: a new increase restarts the cool-down for all of them.
        Instant pendingEffectiveAt = newIncrease ? now.plus(properties.limitIncreaseCooldown())
                : anyPending ? current.pendingEffectiveAt() : null;

        setting.setDailyDepositLimit(daily.current());
        setting.setWeeklyDepositLimit(weekly.current());
        setting.setMonthlyDepositLimit(monthly.current());
        setting.setPendingDailyDepositLimit(daily.pending());
        setting.setPendingWeeklyDepositLimit(weekly.pending());
        setting.setPendingMonthlyDepositLimit(monthly.pending());
        setting.setPendingEffectiveAt(pendingEffectiveAt);
        // TODO: session limit increases take effect immediately; confirm with compliance whether they need the cool-down too.
        setting.setSessionLimitMinutes(request.sessionLimitMinutes());
        if (rgMapper.updateLimits(setting) == 0) {
            throw new BizException(UserErrorCode.RG_CONCURRENT_UPDATE);
        }
        RgSettingsResponse response = toResponse(setting, now);
        audit(userId, "LIMITS_UPDATED", response);
        return response;
    }

    // ---------------------------------------------------------------- restrictions

    public RgSettingsResponse selfExclude(long userId, SelfExclusionRequest request) {
        Instant until = selfExclusionEnd(request, clock.instant());
        transactionTemplate.executeWithoutResult(tx -> {
            rgMapper.insertIfAbsent(userId);
            rgMapper.extendSelfExclusion(userId, until);
            audit(userId, "SELF_EXCLUSION", Map.of("until", until.toString(), "permanent", request.permanent()));
        });
        enforceRestriction(userId, WalletLockService.REASON_SELF_EXCLUSION);
        return settings(userId);
    }

    public RgSettingsResponse coolOff(long userId, CoolOffRequest request) {
        int hours = request.hours();
        BizException.check(hours >= properties.coolOffMinHours() && hours <= properties.coolOffMaxHours(),
                UserErrorCode.RG_INVALID_PERIOD,
                "hours must be between " + properties.coolOffMinHours() + " and " + properties.coolOffMaxHours());
        Instant until = clock.instant().plus(Duration.ofHours(hours));
        transactionTemplate.executeWithoutResult(tx -> {
            rgMapper.insertIfAbsent(userId);
            rgMapper.extendCoolOff(userId, until);
            audit(userId, "COOL_OFF", Map.of("until", until.toString(), "hours", hours));
        });
        enforceRestriction(userId, WalletLockService.REASON_COOL_OFF);
        return settings(userId);
    }

    /**
     * Wallet first: it is what stops bets from game sessions that are already open. A failed wallet call leaves
     * rg_lock_state = 1 for the retry job; the restriction itself is already committed and gates new game launches.
     */
    private void enforceRestriction(long userId, String reason) {
        if (walletLock.lockBetting(userId, reason)) {
            rgMapper.markWalletLocked(userId);
        }
        sessionService.revokeAll(userId);
        // TODO: notify promotion/CRM so marketing to this player stops (needs an RG event topic in common-mq).
    }

    private Instant selfExclusionEnd(SelfExclusionRequest request, Instant now) {
        if (request.permanent()) {
            BizException.check(request.periodDays() == null, UserErrorCode.RG_INVALID_PERIOD,
                    "periodDays must be empty for a permanent self-exclusion");
            return PERMANENT;
        }
        Integer days = request.periodDays();
        BizException.check(days != null && days >= properties.selfExclusionMinDays() && days <= properties.selfExclusionMaxDays(),
                UserErrorCode.RG_INVALID_PERIOD,
                "periodDays must be between " + properties.selfExclusionMinDays() + " and " + properties.selfExclusionMaxDays());
        return now.plus(Duration.ofDays(days));
    }

    // ---------------------------------------------------------------- jobs

    /** Re-sends BET_LOCKED for a restriction whose wallet call failed. */
    public JobOutcome retryWalletLock(UserRgSetting setting, Instant now) {
        String reason = activeRestrictionReason(setting, now);
        if (reason == null) {
            return JobOutcome.SKIPPED; // already ended; rgExpiryJob clears it
        }
        if (!walletLock.lockBetting(setting.getUserId(), reason)) {
            return JobOutcome.FAILED;
        }
        rgMapper.markWalletLocked(setting.getUserId());
        return JobOutcome.DONE;
    }

    /**
     * Lifts the RG wallet lock after every restriction has ended, but only for players who may play again
     * (account ACTIVE, KYC sufficient). Ineligible players stay locked and are re-checked on the next run.
     */
    public JobOutcome releaseExpiredLock(UserRgSetting setting, Instant now) {
        long userId = setting.getUserId();
        UserAccount account = MasterRoute.run(() -> accountMapper.selectById(userId));
        if (account == null || !playerStatusService.evaluate(account, setting, now).canPlay()) {
            return JobOutcome.SKIPPED;
        }
        try {
            if (!walletLock.releaseBetting(userId)) {
                return JobOutcome.SKIPPED;
            }
        } catch (Exception e) {
            log.warn("wallet unlock failed, user={}", userId, e);
            return JobOutcome.FAILED;
        }
        if (rgMapper.clearLockState(userId, setting.getVersion()) == 1) {
            audit(userId, "WALLET_LOCK_RELEASED", Map.of("version", setting.getVersion()));
            return JobOutcome.DONE;
        }
        // The row changed while the wallet was being unlocked: if that was a new restriction, lock again.
        UserRgSetting fresh = MasterRoute.run(() -> rgMapper.selectById(userId));
        String reason = fresh == null ? null : activeRestrictionReason(fresh, clock.instant());
        if (reason != null) {
            walletLock.lockBetting(userId, reason);
        }
        return JobOutcome.SKIPPED;
    }

    // ---------------------------------------------------------------- helpers

    private static String activeRestrictionReason(UserRgSetting setting, Instant now) {
        if (setting.isSelfExcluded(now)) {
            return WalletLockService.REASON_SELF_EXCLUSION;
        }
        if (setting.isCoolingOff(now)) {
            return WalletLockService.REASON_COOL_OFF;
        }
        return null;
    }

    private void audit(long userId, String action, Object detail) {
        UserRgLog entry = new UserRgLog();
        entry.setUserId(userId);
        entry.setUserLine(UserLine.orDefault(accountMapper.selectUserLine(userId)));
        entry.setAction(action);
        entry.setDetail(JsonUtils.toJson(detail));
        rgLogMapper.insert(entry);
    }

    private static BigDecimal normalize(BigDecimal limit) {
        return limit == null ? null : Money.normalizeNonNegative(limit);
    }

    private static Instant activeUntil(Instant until, Instant now) {
        return until != null && until.isAfter(now) ? until : null;
    }

    private static RgSettingsResponse toResponse(UserRgSetting setting, Instant now) {
        RgLimitRules.Limits limits = RgLimitRules.effective(setting, now);
        List<RgSettingsResponse.PendingIncrease> pending = new ArrayList<>(3);
        addPending(pending, "DAILY", limits.pendingDaily(), limits.pendingEffectiveAt());
        addPending(pending, "WEEKLY", limits.pendingWeekly(), limits.pendingEffectiveAt());
        addPending(pending, "MONTHLY", limits.pendingMonthly(), limits.pendingEffectiveAt());
        return new RgSettingsResponse(limits.daily(), limits.weekly(), limits.monthly(), setting.getSessionLimitMinutes(),
                pending, activeUntil(setting.getSelfExcludedUntil(), now), activeUntil(setting.getCoolOffUntil(), now));
    }

    private static void addPending(List<RgSettingsResponse.PendingIncrease> out, String period, BigDecimal pending, Instant effectiveAt) {
        if (pending != null) {
            out.add(new RgSettingsResponse.PendingIncrease(period, RgLimitRules.isRemoval(pending) ? null : pending, effectiveAt));
        }
    }
}
