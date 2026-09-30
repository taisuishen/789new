package com.bingo789.user.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.user.UserErrorCode;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import com.bingo789.user.config.ComplianceProperties;
import com.bingo789.user.entity.UserAccount;
import com.bingo789.user.entity.UserRgSetting;
import com.bingo789.user.mapper.UserAccountMapper;
import com.bingo789.user.mapper.UserRgSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * The single place where play / deposit / withdraw eligibility is decided.
 * <p>
 * Reasons, in check order: ACCOUNT_SUSPENDED, ACCOUNT_CLOSED, SELF_EXCLUDED, COOL_OFF, KYC_{NONE|PENDING|REJECTED}.
 * Deposits are also blocked during a cool-off (stricter than the PlayerStatusView javadoc, which only names
 * self-exclusion).
 */
@Service
@RequiredArgsConstructor
public class PlayerStatusService {

    public static final String SELF_EXCLUDED = "SELF_EXCLUDED";
    public static final String COOL_OFF = "COOL_OFF";
    public static final String SHADOW_ACCOUNT = "SHADOW_ACCOUNT";

    private final UserAccountMapper accountMapper;
    private final UserRgSettingMapper rgMapper;
    private final ComplianceProperties compliance;
    private final Clock clock;

    /** Reads from the primary: this is a compliance gate and must not act on replica lag. */
    public PlayerStatusView status(long userId) {
        return MasterRoute.run(() -> {
            UserAccount account = accountMapper.selectById(userId);
            if (account == null) {
                throw new BizException(UserErrorCode.USER_NOT_FOUND);
            }
            return evaluate(account, rgMapper.selectById(userId), clock.instant());
        });
    }

    public PlayerStatusView evaluate(UserAccount account, UserRgSetting rg, Instant now) {
        return evaluate(account, rg, now, compliance);
    }

    static PlayerStatusView evaluate(UserAccount account, UserRgSetting rg, Instant now, ComplianceProperties compliance) {
        AccountStatus status = account.getStatus();
        if (account.isShadow()) {
            // a stand-in for back-office viewers; it never plays, pays or withdraws
            return new PlayerStatusView(account.getId(), UserLine.orDefault(account.getUserLine()),
                    account.getDefaultCurrency(), status, account.getKycStatus(), false, false, false, null, SHADOW_ACCOUNT);
        }
        KycStatus kyc = account.getKycStatus();
        boolean active = status == AccountStatus.ACTIVE;
        boolean selfExcluded = rg != null && rg.isSelfExcluded(now);
        boolean coolingOff = rg != null && rg.isCoolingOff(now);
        boolean kycVerified = kyc == KycStatus.VERIFIED;

        boolean canPlay = active && !selfExcluded && !coolingOff && (kycVerified || !compliance.kycRequiredForPlay());
        boolean canDeposit = active && !selfExcluded && !coolingOff && (kycVerified || !compliance.kycRequiredForDeposit());
        // Excluded players must still be able to take their money out.
        boolean canWithdraw = status != AccountStatus.CLOSED && kycVerified;

        String reason;
        if (canPlay && canDeposit && canWithdraw) {
            reason = null;
        } else if (!active) {
            reason = "ACCOUNT_" + status.name();
        } else if (selfExcluded) {
            reason = SELF_EXCLUDED;
        } else if (coolingOff) {
            reason = COOL_OFF;
        } else {
            reason = "KYC_" + kyc.name();
        }
        return new PlayerStatusView(account.getId(), UserLine.orDefault(account.getUserLine()),
                account.getDefaultCurrency(), status, kyc, canPlay, canDeposit, canWithdraw,
                selfExcluded ? rg.getSelfExcludedUntil() : null, reason);
    }
}
