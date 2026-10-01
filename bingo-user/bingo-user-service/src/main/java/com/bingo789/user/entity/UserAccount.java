package com.bingo789.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Player account. Government ID numbers and KYC documents are never stored here; they stay with bingo-kyc.
 * email, phone and dateOfBirth hold CIPHERTEXT (PiiCipher, keys in DEW): encrypt when writing, decrypt only where the
 * value is shown. Lookups and the one-account-per-person rule use the blind indexes emailHash / phoneHash.
 */
@Getter
@Setter
@TableName("user_account")
public class UserAccount {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** Stored lower-case. */
    private String username;
    private String passwordHash;
    /** Encrypted lower-cased email. */
    private String email;
    /** PiiCipher.blindIndex of the lower-cased email. */
    private String emailHash;
    /** Encrypted E.164 phone. */
    private String phone;
    /** PiiCipher.blindIndex of the E.164 phone. */
    private String phoneHash;
    /** Encrypted ISO date (yyyy-MM-dd). */
    private String dateOfBirth;
    private String countryCode;
    private String defaultCurrency;
    private AccountStatus status;
    private KycStatus kycStatus;
    /** Applicant reference at the KYC vendor. */
    private String kycVendorRef;
    /** Current user line (UserLine); copied onto every business row written for this player. */
    private Integer userLine;
    /** Bumped by every line migration. */
    private Integer lineVersion;
    private AccountType accountType;
    /** Upline agent account; null for direct players. */
    private Long parentAgentId;
    /** Upline agent's username, denormalised for reports. */
    private String parentAgentName;
    /** Registration channel / campaign code; null when unattributed. */
    private String registerChannel;
    private Instant createdAt;
    private Instant updatedAt;

    public boolean isShadow() {
        return accountType == AccountType.SHADOW;
    }
}
