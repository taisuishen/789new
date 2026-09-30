package com.bingo789.kyc.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One KYC submission; {@link #status} holds {@link KycRecordStatus#code()}. */
@Getter
@Setter
@TableName("kyc_record")
public class KycRecord {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Integer userLine;
    private Integer identityType;
    private String idFrontKey;
    private String idBackKey;
    private String selfieKey;
    private Integer status;
    private String runpodJobId;
    private Integer submitAttempts;
    private LocalDateTime nextSubmitAt;
    private String lastError;
    private String decision;
    /** JSON array. */
    private String reasons;
    /** JSON array. */
    private String reasonsEn;
    private String gender;
    private BigDecimal faceDistance;
    private BigDecimal faceThreshold;
    /** Full worker report (PII). */
    private String resultJson;
    private Integer userSynced;
    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public KycRecordStatus recordStatus() {
        return KycRecordStatus.of(status);
    }
}
