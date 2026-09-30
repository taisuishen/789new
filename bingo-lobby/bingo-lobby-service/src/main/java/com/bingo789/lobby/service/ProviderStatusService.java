package com.bingo789.lobby.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.lobby.LobbyErrorCode;
import com.bingo789.lobby.api.dto.ProviderStatusCommand;
import com.bingo789.lobby.api.enums.ProviderStatus;
import com.bingo789.lobby.mapper.GameProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Two writers share game_provider.status: game-integration (circuit breaker automation) and operators.
 * Automation uses conditional updates so it can never override an operator-set MAINTENANCE or DISABLED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderStatusService {

    private static final int MAX_REASON_LENGTH = 255;

    private final GameProviderMapper providerMapper;
    private final CatalogCache catalogCache;

    public void applyAutomatic(ProviderStatusCommand command) {
        ProviderStatus target = command.status();
        ProviderStatus expected = switch (target) {
            case AUTO_MAINTENANCE -> ProviderStatus.ACTIVE;
            case ACTIVE -> ProviderStatus.AUTO_MAINTENANCE;
            default -> throw new BizException(LobbyErrorCode.INVALID_STATUS,
                    "automation may only set ACTIVE or AUTO_MAINTENANCE");
        };
        int updated = providerMapper.transitionStatus(command.providerCode(), expected.name(), target.name(),
                truncate(command.reason()), now());
        if (updated == 1) {
            log.warn("provider {} switched {} -> {} by automation: {}", command.providerCode(), expected, target, command.reason());
            catalogCache.invalidate();
        } else {
            // already in the target state, or an operator status is in force
            log.info("automatic status {} for provider {} not applied (current status is not {})",
                    target, command.providerCode(), expected);
        }
    }

    public void applyOperator(String providerCode, ProviderStatus target, String reason) {
        BizException.check(target == ProviderStatus.ACTIVE || target == ProviderStatus.MAINTENANCE
                || target == ProviderStatus.DISABLED, LobbyErrorCode.INVALID_STATUS, "AUTO_MAINTENANCE is reserved for automation");
        int updated = providerMapper.setStatus(providerCode, target.name(), truncate(reason), now());
        BizException.check(updated == 1, LobbyErrorCode.PROVIDER_NOT_FOUND);
        log.warn("provider {} set to {} by operator: {}", providerCode, target, reason);
        catalogCache.invalidate();
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(BingoTime.ZONE);
    }
}
