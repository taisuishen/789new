package com.bingo789.user.web;

import com.bingo789.common.core.line.LineScope;
import com.bingo789.user.api.UserApi;
import com.bingo789.user.api.dto.GameTokenView;
import com.bingo789.user.api.dto.IssueGameTokenCommand;
import com.bingo789.user.api.dto.MigrateUserLineCommand;
import com.bingo789.user.api.dto.PlayerProfileView;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.dto.RgLimitsView;
import com.bingo789.user.api.dto.UpdateKycStatusCommand;
import com.bingo789.user.api.dto.UserLineMigrationView;
import com.bingo789.user.api.dto.UserLineView;
import com.bingo789.user.service.GameTokenService;
import com.bingo789.user.service.KycStatusService;
import com.bingo789.user.service.PlayerDirectoryService;
import com.bingo789.user.service.PlayerStatusService;
import com.bingo789.user.service.RgService;
import com.bingo789.user.service.UserLineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cluster-internal endpoints; the player gateway blocks /internal/**.
 * Unknown users are HTTP 404 and a closed compliance gate on issueGameToken is HTTP 403 (Result body);
 * verifyGameToken never fails, it returns {@link GameTokenView#invalid()}.
 */
@RestController
@RequiredArgsConstructor
public class UserInternalController implements UserApi {

    private final PlayerStatusService playerStatusService;
    private final RgService rgService;
    private final GameTokenService gameTokenService;
    private final UserLineService userLineService;
    private final PlayerDirectoryService playerDirectoryService;
    private final KycStatusService kycStatusService;

    @Override
    public PlayerStatusView playerStatus(@PathVariable("userId") long userId) {
        return playerStatusService.status(userId);
    }

    @Override
    public RgLimitsView rgLimits(@PathVariable("userId") long userId) {
        return rgService.effectiveLimits(userId);
    }

    @Override
    public GameTokenView issueGameToken(@Valid @RequestBody IssueGameTokenCommand command) {
        return gameTokenService.issue(command);
    }

    @Override
    public GameTokenView verifyGameToken(@PathVariable("token") String token) {
        return gameTokenService.verify(token);
    }

    @Override
    public List<UserLineView> userLines(@RequestBody List<Long> userIds) {
        return playerDirectoryService.lines(userIds);
    }

    @Override
    public UserLineMigrationView migrateLine(@PathVariable("userId") long userId,
                                             @Valid @RequestBody MigrateUserLineCommand command) {
        return userLineService.migrate(userId, command);
    }

    @Override
    public List<PlayerProfileView> searchPlayers(@RequestParam("username") String username,
                                                 @RequestParam("lines") String lines) {
        return playerDirectoryService.searchByUsername(username, LineScope.parse(lines));
    }

    @Override
    public PlayerProfileView profile(@PathVariable("userId") long userId, @RequestParam("lines") String lines) {
        return playerDirectoryService.profile(userId, LineScope.parse(lines));
    }

    @Override
    public boolean updateKycStatus(@PathVariable("userId") long userId, @Valid @RequestBody UpdateKycStatusCommand command) {
        return kycStatusService.update(userId, command);
    }
}
