package com.bingo789.user.service;

import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.UpdateWalletStatusCommand;
import com.bingo789.wallet.api.enums.WalletStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Wallet BET_LOCKED is the enforcement point of self-exclusion / cool-off in seamless-wallet mode: providers keep
 * calling the wallet for game sessions that were opened before the restriction, and the wallet rejects the bets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletLockService {

    public static final String REASON_SELF_EXCLUSION = "SELF_EXCLUSION";
    public static final String REASON_COOL_OFF = "COOL_OFF";

    private final WalletClient walletClient;

    /** @return true when the wallet confirmed; on false the caller leaves the lock pending for the retry job */
    public boolean lockBetting(long userId, String reason) {
        try {
            walletClient.updateStatus(new UpdateWalletStatusCommand(userId, null, WalletStatus.BET_LOCKED, reason));
            return true;
        } catch (Exception e) {
            log.error("wallet BET_LOCKED failed, user={}, reason={}; rgWalletLockRetryJob will retry", userId, reason, e);
            return false;
        }
    }

    /**
     * Releases the RG locks after every restriction has ended. Wallet locks are keyed by reason, so this only lifts
     * SELF_EXCLUSION / COOL_OFF; locks placed by risk (AML_HOLD) or KYC stay in force. Releasing an absent lock is a
     * no-op. Throws when the wallet cannot be reached, so the job retries on its next run.
     *
     * @return true when the RG locks are released
     */
    public boolean releaseBetting(long userId) {
        walletClient.updateStatus(new UpdateWalletStatusCommand(userId, null, WalletStatus.ACTIVE, REASON_SELF_EXCLUSION));
        walletClient.updateStatus(new UpdateWalletStatusCommand(userId, null, WalletStatus.ACTIVE, REASON_COOL_OFF));
        return true;
    }
}
