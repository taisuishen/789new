package com.bingo789.user.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.user.UserErrorCode;
import com.bingo789.user.api.dto.MigrateUserLineCommand;
import com.bingo789.user.api.dto.UserLineMigrationView;
import com.bingo789.user.entity.AccountType;
import com.bingo789.user.entity.UserAccount;
import com.bingo789.user.entity.UserLineMigration;
import com.bingo789.user.entity.UserShadow;
import com.bingo789.user.mapper.UserAccountMapper;
import com.bingo789.user.mapper.UserLineMigrationMapper;
import com.bingo789.user.mapper.UserShadowMapper;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.UpdateUserLineCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Line migration: the player's account moves to the target line (only {@code user_line} changes; the player keeps
 * id, login, wallet and history), and a shadow copy of the profile is left in the old line, so staff who may only
 * see the old line find the shadow instead of the player. Rows written before the move keep their old line
 * (history is never rewritten); rows written afterwards carry the new one.
 * <p>
 * Coming back to a line that still holds the player's shadow removes that shadow, so a line never shows both.
 * The wallet keeps its own copy of the line for the ledger; it is updated after commit and retried by
 * {@link com.bingo789.user.job.UserLineJobs} until acknowledged (versioned, so retries never apply an older line).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserLineService {

    /** Never matches a BCrypt check: shadow accounts cannot log in. */
    static final String UNUSABLE_PASSWORD = "!";

    private final UserAccountMapper accountMapper;
    private final UserShadowMapper shadowMapper;
    private final UserLineMigrationMapper migrationMapper;
    private final WalletClient walletClient;
    private final TransactionTemplate transactionTemplate;

    public UserLineMigrationView migrate(long userId, MigrateUserLineCommand command) {
        int target = UserLine.require(command.targetLine());
        UserLineMigrationView view = transactionTemplate.execute(tx -> migrateInTransaction(userId, target, command));
        log.info("user {} moved from line {} to line {} by {} (shadow {})",
                userId, view.fromLine(), view.toLine(), command.operatorId(), view.shadowUserId());
        syncWallet(userId);
        return view;
    }

    private UserLineMigrationView migrateInTransaction(long userId, int target, MigrateUserLineCommand command) {
        UserAccount account = accountMapper.selectById(userId);
        BizException.check(account != null && !account.isShadow(), UserErrorCode.USER_NOT_FOUND);
        int from = account.getUserLine();
        BizException.check(from != target, UserErrorCode.SAME_USER_LINE);
        if (accountMapper.moveLine(userId, from, target, account.getLineVersion()) == 0) {
            throw new BizException(UserErrorCode.LINE_MIGRATION_CONFLICT);
        }
        int version = account.getLineVersion() + 1;

        Long retired = null;
        UserShadow comingBack = shadowMapper.selectByUserAndLine(userId, target);
        if (comingBack != null) {
            // the real account is visible on the target line again; its stand-in there must go
            accountMapper.deleteById(comingBack.getShadowUserId());
            shadowMapper.deleteById(comingBack.getShadowUserId());
            retired = comingBack.getShadowUserId();
        }
        UserShadow existing = shadowMapper.selectByUserAndLine(userId, from);
        long shadowId = existing != null ? existing.getShadowUserId() : createShadow(account, from);

        UserLineMigration migration = new UserLineMigration();
        migration.setUserId(userId);
        migration.setFromLine(from);
        migration.setToLine(target);
        migration.setLineVersion(version);
        migration.setShadowUserId(shadowId);
        migration.setRetiredShadowUserId(retired);
        migration.setOperatorId(command.operatorId());
        migration.setReason(command.reason());
        migration.setWalletSynced(0);
        migrationMapper.insert(migration);
        return new UserLineMigrationView(userId, from, target, version, shadowId, retired);
    }

    /** Copies what line staff see of the player; the shadow looks registered at the same time, by the same agent. */
    private long createShadow(UserAccount player, int line) {
        UserAccount shadow = new UserAccount();
        shadow.setUsername(player.getUsername());
        shadow.setPasswordHash(UNUSABLE_PASSWORD);
        shadow.setEmail(player.getEmail());
        shadow.setPhone(player.getPhone());
        shadow.setDateOfBirth(player.getDateOfBirth());
        shadow.setCountryCode(player.getCountryCode());
        shadow.setDefaultCurrency(player.getDefaultCurrency());
        shadow.setStatus(player.getStatus());
        shadow.setKycStatus(player.getKycStatus());
        shadow.setUserLine(line);
        shadow.setLineVersion(0);
        shadow.setAccountType(AccountType.SHADOW);
        shadow.setParentAgentId(player.getParentAgentId());
        shadow.setParentAgentName(player.getParentAgentName());
        shadow.setRegisterChannel(player.getRegisterChannel());
        shadow.setCreatedAt(player.getCreatedAt());
        accountMapper.insert(shadow);

        UserShadow link = new UserShadow();
        link.setShadowUserId(shadow.getId());
        link.setUserId(player.getId());
        link.setUserLine(line);
        shadowMapper.insert(link);
        return shadow.getId();
    }

    /**
     * Sends the player's CURRENT line and version to the wallet (so a retry after several quick migrations sends the
     * latest, and the wallet ignores anything older than what it has).
     *
     * @return true when the wallet acknowledged
     */
    public boolean syncWallet(long userId) {
        UserAccount account = MasterRoute.run(() -> accountMapper.selectById(userId));
        if (account == null || account.isShadow() || account.getLineVersion() < 1) {
            return true;
        }
        try {
            walletClient.updateUserLine(new UpdateUserLineCommand(userId, account.getUserLine(), account.getLineVersion()));
        } catch (Exception e) {
            log.warn("wallet line update failed for user {} (line {}, version {}), the retry job will resend",
                    userId, account.getUserLine(), account.getLineVersion(), e);
            return false;
        }
        migrationMapper.markWalletSynced(userId, account.getLineVersion());
        return true;
    }
}
