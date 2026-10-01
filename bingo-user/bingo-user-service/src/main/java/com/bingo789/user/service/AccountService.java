package com.bingo789.user.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.crypto.PiiCipher;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.user.UserErrorCode;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import com.bingo789.user.config.ComplianceProperties;
import com.bingo789.user.config.SessionProperties;
import com.bingo789.user.entity.AccountType;
import com.bingo789.user.entity.UserAccount;
import com.bingo789.user.entity.UserLoginLog;
import com.bingo789.user.entity.UserRgSetting;
import com.bingo789.user.mapper.UserAccountMapper;
import com.bingo789.user.mapper.UserLoginLogMapper;
import com.bingo789.user.mapper.UserRgSettingMapper;
import com.bingo789.user.web.dto.LoginRequest;
import com.bingo789.user.web.dto.LoginResponse;
import com.bingo789.user.web.dto.MeResponse;
import com.bingo789.user.web.dto.RegisterRequest;
import com.bingo789.user.web.dto.RegisterResponse;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.OpenWalletCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserAccountMapper accountMapper;
    private final UserRgSettingMapper rgMapper;
    private final UserLoginLogMapper loginLogMapper;
    private final PasswordService passwordService;
    private final LoginThrottle loginThrottle;
    private final SessionService sessionService;
    private final PlayerStatusService playerStatusService;
    private final WalletClient walletClient;
    private final ComplianceProperties compliance;
    private final SessionProperties sessionProperties;
    private final TransactionTemplate transactionTemplate;
    private final PiiCipher pii;
    private final Clock clock;

    public RegisterResponse register(RegisterRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phone();
        String country = request.countryCode().toUpperCase(Locale.ROOT);
        String currency = request.currency().toUpperCase(Locale.ROOT);

        BizException.check(compliance.isCountryAllowed(country), UserErrorCode.COUNTRY_NOT_ALLOWED);
        BizException.check(compliance.isCurrencyAllowed(currency), UserErrorCode.CURRENCY_NOT_SUPPORTED);
        LocalDate today = LocalDate.now(clock.withZone(compliance.zone()));
        BizException.check(Period.between(request.dateOfBirth(), today).getYears() >= compliance.minAge(), UserErrorCode.UNDERAGE);
        String emailHash = pii.blindIndex(email);
        String phoneHash = pii.blindIndex(phone);
        checkNotRegistered(username, emailHash, phoneHash);
        UserAccount agent = referringAgent(request.agentId());
        String passwordHash = passwordService.hash(request.password());

        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordHash);
        account.setEmail(pii.encrypt(email));
        account.setEmailHash(emailHash);
        account.setPhone(pii.encrypt(phone));
        account.setPhoneHash(phoneHash);
        account.setDateOfBirth(pii.encrypt(request.dateOfBirth().toString()));
        account.setCountryCode(country);
        account.setDefaultCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        account.setKycStatus(KycStatus.NONE);
        account.setUserLine(UserLine.DEFAULT);
        account.setLineVersion(0);
        account.setAccountType(AccountType.PLAYER);
        if (agent != null) {
            account.setParentAgentId(agent.getId());
            account.setParentAgentName(agent.getUsername());
        }
        account.setRegisterChannel(request.registerChannel());
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                accountMapper.insert(account);
                rgMapper.insertIfAbsent(account.getId());
            });
        } catch (RuntimeException e) {
            if (DuplicateKeys.isDuplicateKey(e)) {
                // lost a race with a concurrent registration of the same username / email / phone
                throw new BizException(UserErrorCode.ACCOUNT_ALREADY_EXISTS);
            }
            throw e;
        }
        openWallet(account.getId(), currency);
        // KYC is submitted by the player afterwards (bingo-kyc: OBS images -> RunPod), which moves kyc_status on.
        // TODO: publish a registration event for risk (multi-account / duplicate-identity screening).
        return new RegisterResponse(account.getId(), username);
    }

    public LoginResponse login(LoginRequest request, ClientInfo client) {
        String username = request.username().toLowerCase(Locale.ROOT);
        loginThrottle.checkAllowed(username);
        UserAccount account = accountMapper.selectByUsername(username);
        if (!passwordService.matches(request.password(), account == null ? null : account.getPasswordHash())) {
            loginThrottle.recordFailure(username);
            throw new BizException(UserErrorCode.INVALID_CREDENTIALS);
        }
        // Checked after the password, so the response does not reveal which usernames exist.
        BizException.check(account.getStatus() != AccountStatus.SUSPENDED, UserErrorCode.ACCOUNT_SUSPENDED);
        BizException.check(account.getStatus() != AccountStatus.CLOSED, UserErrorCode.ACCOUNT_CLOSED);
        loginThrottle.reset(username);

        // Self-excluded players may still log in: they must be able to withdraw. Play is gated by playerStatus.
        Duration ttl = sessionTtl(rgMapper.selectById(account.getId()));
        // Logged before the session exists, so there is never an unaudited login.
        loginLogMapper.insert(loginLog(account, client));
        String token = sessionService.create(account.getId(), ttl);
        return new LoginResponse(token, clock.instant().plus(ttl), account.getId());
    }

    public void logout(String sessionToken) {
        sessionService.revoke(sessionToken);
    }

    public MeResponse me(long userId, String sessionToken) {
        UserAccount account = accountMapper.selectById(userId);
        if (account == null) {
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        UserRgSetting rg = rgMapper.selectById(userId);
        PlayerStatusView status = playerStatusService.evaluate(account, rg, clock.instant());
        // A player-set session limit caps the session length at login, so such sessions are never extended.
        if (sessionToken != null && (rg == null || rg.getSessionLimitMinutes() == null)) {
            sessionService.refresh(userId, sessionToken);
        }
        String dateOfBirth = pii.decrypt(account.getDateOfBirth());
        return new MeResponse(account.getId(), account.getUsername(), pii.decrypt(account.getEmail()),
                pii.decrypt(account.getPhone()), dateOfBirth == null ? null : LocalDate.parse(dateOfBirth),
                account.getCountryCode(), account.getDefaultCurrency(),
                account.getStatus(), account.getKycStatus(), status.canPlay(), status.canDeposit(), status.canWithdraw(),
                status.reason(), status.selfExcludedUntil());
    }

    private void checkNotRegistered(String username, String emailHash, String phoneHash) {
        // Friendly errors only; the unique indexes are the real guard.
        List<UserAccount> existing = accountMapper.selectConflicts(username, emailHash, phoneHash);
        BizException.check(existing.stream().noneMatch(a -> username.equalsIgnoreCase(a.getUsername())), UserErrorCode.USERNAME_TAKEN);
        BizException.check(existing.stream().noneMatch(a -> emailHash.equals(a.getEmailHash())), UserErrorCode.EMAIL_TAKEN);
        BizException.check(existing.stream().noneMatch(a -> phoneHash.equals(a.getPhoneHash())), UserErrorCode.PHONE_TAKEN);
    }

    /**
     * The referral link's agent must be an active real account. New players always start on the default line,
     * whatever the agent's line (a moved agent is shown to other lines through its shadow).
     * TODO: restrict to accounts with an agent role once the agent module exists.
     */
    private UserAccount referringAgent(Long agentId) {
        if (agentId == null) {
            return null;
        }
        UserAccount agent = accountMapper.selectById(agentId);
        BizException.check(agent != null && !agent.isShadow() && agent.getStatus() == AccountStatus.ACTIVE,
                UserErrorCode.AGENT_NOT_FOUND);
        return agent;
    }

    /** Wallet open is idempotent and payment retries it on the first deposit, so a failure here is not fatal. */
    private void openWallet(long userId, String currency) {
        try {
            walletClient.open(new OpenWalletCommand(userId, currency));
        } catch (Exception e) {
            log.warn("wallet open failed for user {} ({}), continuing", userId, currency, e);
        }
    }

    private Duration sessionTtl(UserRgSetting rg) {
        Duration ttl = sessionProperties.ttl();
        if (rg != null && rg.getSessionLimitMinutes() != null) {
            Duration limit = Duration.ofMinutes(rg.getSessionLimitMinutes());
            return limit.compareTo(ttl) < 0 ? limit : ttl;
        }
        return ttl;
    }

    private static UserLoginLog loginLog(UserAccount account, ClientInfo client) {
        UserLoginLog entry = new UserLoginLog();
        entry.setUserId(account.getId());
        entry.setUserLine(account.getUserLine());
        entry.setIp(client.ip());
        entry.setDeviceId(client.deviceId());
        entry.setUserAgent(client.userAgent());
        entry.setCountryCode(client.countryCode());
        return entry;
    }
}
