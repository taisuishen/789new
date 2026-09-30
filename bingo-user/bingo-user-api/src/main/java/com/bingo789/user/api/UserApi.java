package com.bingo789.user.api;

import com.bingo789.user.api.dto.GameTokenView;
import com.bingo789.user.api.dto.IssueGameTokenCommand;
import com.bingo789.user.api.dto.MigrateUserLineCommand;
import com.bingo789.user.api.dto.PlayerProfileView;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.dto.RgLimitsView;
import com.bingo789.user.api.dto.UpdateKycStatusCommand;
import com.bingo789.user.api.dto.UserLineMigrationView;
import com.bingo789.user.api.dto.UserLineView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface UserApi {

    String PREFIX = "/internal/user";

    @GetMapping(PREFIX + "/players/{userId}/status")
    PlayerStatusView playerStatus(@PathVariable("userId") long userId);

    @GetMapping(PREFIX + "/players/{userId}/rg-limits")
    RgLimitsView rgLimits(@PathVariable("userId") long userId);

    @PostMapping(PREFIX + "/game-tokens")
    GameTokenView issueGameToken(@RequestBody IssueGameTokenCommand command);

    @GetMapping(PREFIX + "/game-tokens/{token}")
    GameTokenView verifyGameToken(@PathVariable("token") String token);

    /** Current line of each player (at most 1000 ids); unknown ids are left out. */
    @PostMapping(PREFIX + "/players/lines")
    List<UserLineView> userLines(@RequestBody List<Long> userIds);

    /**
     * Moves a player to another line and leaves a shadow account in the old one, so viewers of the old line keep
     * finding "the player" but never see the real account or its new data. Back-office only (audited).
     */
    @PostMapping(PREFIX + "/players/{userId}/line-migration")
    UserLineMigrationView migrateLine(@PathVariable("userId") long userId, @RequestBody MigrateUserLineCommand command);

    /**
     * Back-office player lookup, restricted to the viewer's lines ({@code lines}: "1,2", or "*" for every line).
     * A viewer who cannot see a migrated player finds the shadow account instead, indistinguishable from a player.
     */
    @GetMapping(PREFIX + "/players/search")
    List<PlayerProfileView> searchPlayers(@RequestParam("username") String username, @RequestParam("lines") String lines);

    /**
     * Profile of {@code userId} as the viewer may see it: the account itself when its line is visible, otherwise the
     * player's shadow in a visible line (e.g. for rows written before a migration), otherwise 404.
     */
    @GetMapping(PREFIX + "/players/{userId}/profile")
    PlayerProfileView profile(@PathVariable("userId") long userId, @RequestParam("lines") String lines);

    /**
     * KYC status from bingo-kyc (PENDING on submission, VERIFIED / REJECTED on the result). Idempotent; returns false
     * when ignored: a VERIFIED player is never changed by another submission, and a finished submission never goes
     * back to PENDING (late or reordered calls).
     */
    @PostMapping(PREFIX + "/players/{userId}/kyc")
    boolean updateKycStatus(@PathVariable("userId") long userId, @RequestBody UpdateKycStatusCommand command);
}
