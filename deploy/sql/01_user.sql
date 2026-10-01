USE bingo_user;

-- Player accounts, responsible gaming, login audit.
--
-- PII: every column marked "PII" must be field-encrypted with Huawei DEW (envelope encryption) before go-live;
-- column types then become VARBINARY and lookups / unique keys move to HMAC blind-index columns.
-- Government ID numbers and KYC documents are never stored here: they stay in bingo-kyc (private OBS bucket +
-- kyc_record), only the verification status and the submission reference are kept.
-- User line (user_line): which back-office staff and reports may see the player (default 1). Every player-related
-- business row in every service stores the line the player had when the row was written.
-- Shadow accounts (account_type = SHADOW): when a player is moved to another line, a shadow copy of the profile is
-- left in the old line, so staff of the old line keep finding "the player" (and nothing of the new line's data).
-- A shadow cannot log in (unusable password hash, excluded from the login / uniqueness columns) and owns no wallet.
-- Its link to the real account is only in user_shadow, never on the account row that line staff may read.
CREATE TABLE IF NOT EXISTS user_account (
    id                BIGINT       NOT NULL COMMENT 'snowflake',
    username          VARCHAR(32)  NOT NULL COMMENT 'stored lower-case; a shadow repeats its player''s username',
    password_hash     VARCHAR(100) NOT NULL COMMENT 'BCrypt; ''!'' (never matches) for shadow accounts',
    email             VARCHAR(512) NOT NULL COMMENT 'PII, encrypted (PiiCipher); lower-cased before encryption',
    email_hash        CHAR(64)     NOT NULL COMMENT 'blind index of the lower-cased email (PiiCipher.blindIndex)',
    phone             VARCHAR(128) NOT NULL COMMENT 'PII, encrypted (PiiCipher); E.164',
    phone_hash        CHAR(64)     NOT NULL COMMENT 'blind index of the E.164 phone',
    date_of_birth     VARCHAR(64)  NOT NULL COMMENT 'PII, encrypted ISO date (yyyy-MM-dd); minimum age checked at registration',
    country_code      CHAR(2)      NOT NULL COMMENT 'ISO 3166-1 alpha-2 country of residence declared at registration',
    default_currency  CHAR(3)      NOT NULL COMMENT 'ISO 4217',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED / CLOSED',
    kyc_status        VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE / PENDING / VERIFIED / REJECTED',
    kyc_vendor_ref    VARCHAR(128) NULL COMMENT 'bingo-kyc kyc_record id of the submission that set kyc_status',
    user_line         INT          NOT NULL DEFAULT 1 COMMENT 'current line (1..99)',
    line_version      INT          NOT NULL DEFAULT 0 COMMENT 'bumped by every line migration; copies elsewhere (wallet) apply newer versions only',
    account_type      VARCHAR(16)  NOT NULL DEFAULT 'PLAYER' COMMENT 'PLAYER / SHADOW',
    parent_agent_id   BIGINT       NULL COMMENT 'upline agent (an account id); NULL = direct player',
    parent_agent_name VARCHAR(32)  NULL COMMENT 'upline agent''s username at registration (denormalised for reports)',
    register_channel  VARCHAR(32)  NULL COMMENT 'registration channel / campaign code from the landing page; NULL = unattributed',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    -- Identity columns of real players only (NULL for shadows, and unique keys ignore NULLs): logins and the
    -- one-account-per-person rule never see shadow accounts. Never written by the application.
    player_username   VARCHAR(32)  GENERATED ALWAYS AS (IF(account_type = 'PLAYER', username, NULL)) STORED,
    player_email_hash CHAR(64)     GENERATED ALWAYS AS (IF(account_type = 'PLAYER', email_hash, NULL)) STORED,
    player_phone_hash CHAR(64)     GENERATED ALWAYS AS (IF(account_type = 'PLAYER', phone_hash, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (player_username),
    -- one account per person (licence condition), on the blind indexes (email / phone are encrypted)
    UNIQUE KEY uk_email (player_email_hash),
    UNIQUE KEY uk_phone (player_phone_hash),
    KEY idx_username_line (username, user_line),
    KEY idx_line_created (user_line, created_at),
    KEY idx_parent_agent (parent_agent_id),
    KEY idx_channel_created (register_channel, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='player account';

-- Real account -> its shadow in each line it has left. Back-office code resolves "who is this for a viewer of
-- line L" through here; staff limited to line L never see this mapping.
CREATE TABLE IF NOT EXISTS user_shadow (
    shadow_user_id BIGINT      NOT NULL COMMENT 'user_account.id of the SHADOW account',
    user_id        BIGINT      NOT NULL COMMENT 'the real player',
    user_line      INT         NOT NULL COMMENT 'line the shadow lives in',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (shadow_user_id),
    UNIQUE KEY uk_user_line (user_id, user_line)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='shadow accounts of migrated players';

-- Append-only audit of line migrations; wallet_synced drives the retry job that copies the line to the wallet.
CREATE TABLE IF NOT EXISTS user_line_migration (
    id                     BIGINT       NOT NULL COMMENT 'snowflake',
    user_id                BIGINT       NOT NULL,
    from_line              INT          NOT NULL,
    to_line                INT          NOT NULL,
    line_version           INT          NOT NULL COMMENT 'user_account.line_version after this migration',
    shadow_user_id         BIGINT       NOT NULL COMMENT 'shadow left in from_line (created, or reused)',
    retired_shadow_user_id BIGINT       NULL COMMENT 'shadow removed from to_line because the player came back',
    operator_id            VARCHAR(64)  NOT NULL,
    reason                 VARCHAR(255) NULL,
    wallet_synced          TINYINT      NOT NULL DEFAULT 0 COMMENT '1 once the wallet acknowledged a version >= line_version',
    wallet_synced_at       DATETIME(3)  NULL,
    created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user (user_id, id),
    KEY idx_wallet_synced (wallet_synced, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user line migrations';

CREATE TABLE IF NOT EXISTS user_rg_setting (
    user_id                       BIGINT        NOT NULL,
    daily_deposit_limit           DECIMAL(20,4) NULL COMMENT 'NULL = no limit',
    weekly_deposit_limit          DECIMAL(20,4) NULL COMMENT 'NULL = no limit',
    monthly_deposit_limit         DECIMAL(20,4) NULL COMMENT 'NULL = no limit',
    session_limit_minutes         INT           NULL COMMENT 'caps the session length at login; NULL = none',
    -- Decreases apply immediately; increases (and removals) wait here for the cool-down.
    -- A pending value whose pending_effective_at has passed IS the effective limit, even before the service
    -- copies it into the column above: always read limits through user-service (UserApi.rgLimits).
    pending_daily_deposit_limit   DECIMAL(20,4) NULL COMMENT 'NULL = nothing pending, -1 = remove the limit',
    pending_weekly_deposit_limit  DECIMAL(20,4) NULL COMMENT 'NULL = nothing pending, -1 = remove the limit',
    pending_monthly_deposit_limit DECIMAL(20,4) NULL COMMENT 'NULL = nothing pending, -1 = remove the limit',
    pending_effective_at          DATETIME(3)   NULL COMMENT 'when the pending increases take effect (request + cool-down)',
    self_excluded_until           DATETIME(3)   NULL COMMENT 'extend-only; 9999-12-31 = permanent',
    cool_off_until                DATETIME(3)   NULL COMMENT 'extend-only',
    rg_lock_state                 TINYINT       NOT NULL DEFAULT 0 COMMENT '0 none, 1 wallet BET_LOCKED requested (retry pending), 2 wallet BET_LOCKED confirmed',
    version                       INT           NOT NULL DEFAULT 0 COMMENT 'optimistic lock, bumped by every limit or restriction change',
    updated_at                    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    KEY idx_lock_state (rg_lock_state, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='responsible gaming settings, one row per player';

-- Append-only audit trail of RG actions for the regulator.
CREATE TABLE IF NOT EXISTS user_rg_log (
    id         BIGINT      NOT NULL COMMENT 'snowflake',
    user_id    BIGINT      NOT NULL,
    user_line  INT         NOT NULL DEFAULT 1,
    action     VARCHAR(32) NOT NULL COMMENT 'LIMITS_UPDATED / SELF_EXCLUSION / COOL_OFF / WALLET_LOCK_RELEASED',
    detail     JSON        NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='responsible gaming audit log';

-- Successful logins. ip / device_id indexes serve multi-account detection by risk.
-- TODO: monthly RANGE partitions on created_at (PK then becomes (id, created_at)) and retention per licence.
CREATE TABLE IF NOT EXISTS user_login_log (
    id           BIGINT       NOT NULL COMMENT 'snowflake',
    user_id      BIGINT       NOT NULL,
    user_line    INT          NOT NULL DEFAULT 1,
    ip           VARCHAR(45)  NOT NULL COMMENT 'IPv4 or IPv6, as resolved by the gateway',
    device_id    VARCHAR(128) NULL COMMENT 'client device fingerprint (X-Device-Id)',
    user_agent   VARCHAR(512) NULL,
    country_code CHAR(2)      NULL COMMENT 'geo-IP country from the WAF',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_created (user_id, created_at),
    KEY idx_device (device_id),
    KEY idx_ip (ip)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='login audit';
