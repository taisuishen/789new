package com.bingo789.kyc.domain;

import com.bingo789.user.api.enums.KycStatus;

/** kyc_record.status (TINYINT). 2, 3 and 4 are final. */
public enum KycRecordStatus {

    /** 待提交: submission to RunPod failed, the retry job resubmits it. */
    PENDING_SUBMIT(0),
    /** 处理中: accepted by RunPod, waiting for the result. */
    PROCESSING(1),
    /** 成功 */
    APPROVED(2),
    /** 拒绝 */
    REJECTED(3),
    /** RunPod 响应失败: no result within the timeout, or the worker reported an error. Rejected. */
    RUNPOD_FAILED(4);

    private final int code;

    KycRecordStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean isFinal() {
        return this == APPROVED || this == REJECTED || this == RUNPOD_FAILED;
    }

    /** What user-service shows for a submission in this state. */
    public KycStatus userStatus() {
        return switch (this) {
            case PENDING_SUBMIT, PROCESSING -> KycStatus.PENDING;
            case APPROVED -> KycStatus.VERIFIED;
            case REJECTED, RUNPOD_FAILED -> KycStatus.REJECTED;
        };
    }

    public static KycRecordStatus of(int code) {
        for (KycRecordStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown KYC record status " + code);
    }
}
