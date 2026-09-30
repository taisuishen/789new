package com.bingo789.user.service;

import com.bingo789.user.api.dto.UpdateKycStatusCommand;
import com.bingo789.user.api.enums.KycStatus;
import com.bingo789.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The player's KYC status as projected from bingo-kyc, which owns the submissions (images, RunPod results). The
 * status gates play / deposit / withdrawal through {@link PlayerStatusService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycStatusService {

    private final UserAccountMapper accountMapper;

    public boolean update(long userId, UpdateKycStatusCommand command) {
        if (command.status() == KycStatus.NONE) {
            throw new IllegalArgumentException("KYC status NONE cannot be set");
        }
        boolean applied = accountMapper.updateKyc(userId, command.status().name(), command.reference()) == 1;
        if (applied) {
            log.info("KYC status of user {} set to {} by submission {} {}", userId, command.status(), command.reference(),
                    command.reason() == null ? "" : "(" + command.reason() + ")");
        } else {
            log.info("KYC status {} of submission {} ignored for user {} (unknown player, verified by another "
                    + "submission, or a late update)", command.status(), command.reference(), userId);
        }
        return applied;
    }
}
