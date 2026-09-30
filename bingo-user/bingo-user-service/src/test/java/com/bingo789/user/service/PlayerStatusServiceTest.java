package com.bingo789.user.service;

import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import com.bingo789.user.config.ComplianceProperties;
import com.bingo789.user.entity.UserAccount;
import com.bingo789.user.entity.UserRgSetting;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ComplianceProperties COMPLIANCE =
            new ComplianceProperties(21, List.of("PH"), List.of("PHP"), true, false, "+08:00");

    @Test
    void verifiedActivePlayerIsUnrestricted() {
        PlayerStatusView view = evaluate(account(AccountStatus.ACTIVE, KycStatus.VERIFIED), null);

        assertThat(view.canPlay()).isTrue();
        assertThat(view.canDeposit()).isTrue();
        assertThat(view.canWithdraw()).isTrue();
        assertThat(view.reason()).isNull();
    }

    @Test
    void selfExcludedPlayerCanOnlyWithdraw() {
        UserRgSetting rg = new UserRgSetting();
        rg.setSelfExcludedUntil(NOW.plusSeconds(60));

        PlayerStatusView view = evaluate(account(AccountStatus.ACTIVE, KycStatus.VERIFIED), rg);

        assertThat(view.canPlay()).isFalse();
        assertThat(view.canDeposit()).isFalse();
        assertThat(view.canWithdraw()).isTrue();
        assertThat(view.reason()).isEqualTo(PlayerStatusService.SELF_EXCLUDED);
        assertThat(view.selfExcludedUntil()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void endedCoolOffNoLongerRestricts() {
        UserRgSetting rg = new UserRgSetting();
        rg.setCoolOffUntil(NOW);

        assertThat(evaluate(account(AccountStatus.ACTIVE, KycStatus.VERIFIED), rg).canPlay()).isTrue();
    }

    @Test
    void pendingKycBlocksPlayAndWithdrawalButNotDeposit() {
        PlayerStatusView view = evaluate(account(AccountStatus.ACTIVE, KycStatus.PENDING), null);

        assertThat(view.canPlay()).isFalse();
        assertThat(view.canDeposit()).isTrue();
        assertThat(view.canWithdraw()).isFalse();
        assertThat(view.reason()).isEqualTo("KYC_PENDING");
    }

    @Test
    void suspendedPlayerCanStillWithdraw() {
        PlayerStatusView view = evaluate(account(AccountStatus.SUSPENDED, KycStatus.VERIFIED), null);

        assertThat(view.canPlay()).isFalse();
        assertThat(view.canDeposit()).isFalse();
        assertThat(view.canWithdraw()).isTrue();
        assertThat(view.reason()).isEqualTo("ACCOUNT_SUSPENDED");
    }

    @Test
    void closedAccountCanDoNothing() {
        PlayerStatusView view = evaluate(account(AccountStatus.CLOSED, KycStatus.VERIFIED), null);

        assertThat(view.canPlay()).isFalse();
        assertThat(view.canDeposit()).isFalse();
        assertThat(view.canWithdraw()).isFalse();
        assertThat(view.reason()).isEqualTo("ACCOUNT_CLOSED");
    }

    private static PlayerStatusView evaluate(UserAccount account, UserRgSetting rg) {
        return PlayerStatusService.evaluate(account, rg, NOW, COMPLIANCE);
    }

    private static UserAccount account(AccountStatus status, KycStatus kyc) {
        UserAccount account = new UserAccount();
        account.setId(1L);
        account.setDefaultCurrency("PHP");
        account.setStatus(status);
        account.setKycStatus(kyc);
        return account;
    }
}
