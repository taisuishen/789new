package com.bingo789.kyc.service;

import com.bingo789.kyc.domain.KycRecord;
import com.bingo789.kyc.domain.KycRecordStatus;
import com.bingo789.kyc.mapper.KycRecordMapper;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.UpdateKycStatusCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pushes a submission's status to user-service (kyc_status PENDING / VERIFIED / REJECTED gates play, deposits and
 * withdrawals). Best effort right after each transition; kycUserSyncJob retries rows still marked user_synced = 0.
 * user-service ignores stale updates, so repeating or reordering them is harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserKycSync {

    private final UserClient userClient;
    private final KycRecordMapper mapper;

    /** @param record the row as currently stored (its status is what gets pushed) */
    public boolean push(KycRecord record) {
        KycRecordStatus status = record.recordStatus();
        try {
            userClient.updateKycStatus(record.getUserId(), new UpdateKycStatusCommand(status.userStatus(),
                    String.valueOf(record.getId()), reason(record, status)));
        } catch (RuntimeException e) {
            log.warn("KYC status of user {} (submission {}, {}) not yet in user-service, the sync job retries: {}",
                    record.getUserId(), record.getId(), status, e.toString());
            return false;
        }
        mapper.markUserSynced(record.getId(), status.code());
        return true;
    }

    private static String reason(KycRecord record, KycRecordStatus status) {
        return switch (status) {
            case PENDING_SUBMIT, PROCESSING -> null;
            case APPROVED -> "approved by the KYC worker";
            case REJECTED -> "rejected by the KYC worker";
            case RUNPOD_FAILED -> record.getLastError() != null ? truncate(record.getLastError()) : "KYC worker failed";
        };
    }

    private static String truncate(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
