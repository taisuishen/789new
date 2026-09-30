package com.bingo789.user.api.dto;

import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;

import java.time.Instant;

/**
 * Back-office view of a player. Contains no contact data (email / phone are served by a separate, audited
 * endpoint once the back office exists).
 *
 * @param shadow true only when the viewer may also see the real account; a viewer limited to the shadow's line
 *               gets {@code false}, so the shadow is indistinguishable from a regular player
 */
public record PlayerProfileView(
        long userId,
        String username,
        int userLine,
        AccountStatus status,
        KycStatus kycStatus,
        String countryCode,
        String defaultCurrency,
        Long parentAgentId,
        String parentAgentName,
        String registerChannel,
        Instant registeredAt,
        boolean shadow) {
}
