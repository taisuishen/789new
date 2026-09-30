package com.bingo789.user.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.line.LineScope;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.user.UserErrorCode;
import com.bingo789.user.api.dto.PlayerProfileView;
import com.bingo789.user.api.dto.UserLineView;
import com.bingo789.user.entity.UserAccount;
import com.bingo789.user.entity.UserShadow;
import com.bingo789.user.mapper.UserAccountMapper;
import com.bingo789.user.mapper.UserShadowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Back-office view of players, always restricted to the viewer's {@link LineScope}. A viewer who cannot see a
 * migrated player's current line finds the player's shadow in a line they can see, presented exactly like a regular
 * player ({@code shadow = false}); only viewers who can also see the real account learn that it is a shadow.
 */
@Service
@RequiredArgsConstructor
public class PlayerDirectoryService {

    public static final int MAX_LINE_LOOKUP = 1000;

    private final UserAccountMapper accountMapper;
    private final UserShadowMapper shadowMapper;

    public List<PlayerProfileView> searchByUsername(String username, LineScope scope) {
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return accountMapper.selectByUsernameInLines(normalized, scope.isAll() ? null : scope.lines()).stream()
                .map(account -> view(account, scope))
                .toList();
    }

    /**
     * The account itself when its line is visible; otherwise its shadow in a visible line (typical for rows written
     * before a migration, which still carry the old line and the real player id); otherwise not found.
     */
    public PlayerProfileView profile(long userId, LineScope scope) {
        UserAccount account = accountMapper.selectById(userId);
        BizException.check(account != null, UserErrorCode.USER_NOT_FOUND);
        if (scope.contains(account.getUserLine())) {
            return view(account, scope);
        }
        if (!account.isShadow()) {
            for (UserShadow shadow : shadowMapper.selectByUser(userId)) {
                if (scope.contains(shadow.getUserLine())) {
                    UserAccount stand = accountMapper.selectById(shadow.getShadowUserId());
                    if (stand != null) {
                        return view(stand, scope);
                    }
                }
            }
        }
        throw new BizException(UserErrorCode.USER_NOT_FOUND);
    }

    /** Current lines, read from the primary (callers stamp them on rows they write). */
    public List<UserLineView> lines(List<Long> userIds) {
        Set<Long> ids = new LinkedHashSet<>(userIds);
        BizException.check(ids.size() <= MAX_LINE_LOOKUP, UserErrorCode.INVALID_LINE_SCOPE,
                "at most " + MAX_LINE_LOOKUP + " user ids per call");
        if (ids.isEmpty()) {
            return List.of();
        }
        return MasterRoute.run(() -> accountMapper.selectLines(ids)).stream()
                .map(account -> new UserLineView(account.getId(), account.getUserLine()))
                .toList();
    }

    private PlayerProfileView view(UserAccount account, LineScope scope) {
        boolean shadow = false;
        if (account.isShadow()) {
            // reveal the shadow only to viewers who can also see the real account
            UserShadow link = shadowMapper.selectById(account.getId());
            Integer realLine = link == null ? null : accountMapper.selectUserLine(link.getUserId());
            shadow = realLine != null && scope.contains(realLine);
        }
        return new PlayerProfileView(account.getId(), account.getUsername(), account.getUserLine(),
                account.getStatus(), account.getKycStatus(), account.getCountryCode(), account.getDefaultCurrency(),
                account.getParentAgentId(), account.getParentAgentName(), account.getRegisterChannel(),
                account.getCreatedAt(), shadow);
    }
}
