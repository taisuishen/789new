USE bingo_payment;

-- All DATETIME columns hold UTC+8 wall-clock time (the JDBC URL forces the session time zone to +08:00).
-- Status columns hold the Java enum names.

CREATE TABLE IF NOT EXISTS payment_channel (
  code        VARCHAR(32)   NOT NULL PRIMARY KEY,
  name        VARCHAR(64)   NOT NULL,
  direction   VARCHAR(16)   NOT NULL COMMENT 'DEPOSIT, PAYOUT, BOTH',
  status      VARCHAR(16)   NOT NULL DEFAULT 'DISABLED' COMMENT 'ENABLED, DISABLED',
  currencies  VARCHAR(128)  NOT NULL COMMENT 'comma-separated ISO 4217 codes, e.g. PHP',
  min_amount  DECIMAL(20,4) NOT NULL,
  max_amount  DECIMAL(20,4) NOT NULL,
  config_ref  VARCHAR(256)  NULL COMMENT 'reference to the channel credentials in Huawei DEW/CSMS; never raw keys',
  sort        INT           NOT NULL DEFAULT 0,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS deposit_order (
  id               BIGINT        NOT NULL PRIMARY KEY,
  order_no         VARCHAR(40)   NOT NULL,
  user_id          BIGINT        NOT NULL,
  user_line        INT           NOT NULL DEFAULT 1 COMMENT 'player''s user line when the order was created (snapshot)',
  currency         VARCHAR(8)    NOT NULL,
  amount           DECIMAL(20,4) NOT NULL,
  channel_code     VARCHAR(32)   NOT NULL,
  channel_order_no VARCHAR(64)   NULL,
  status           VARCHAR(16)   NOT NULL COMMENT 'CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED',
  first_deposit    TINYINT       NOT NULL DEFAULT 0 COMMENT 'set when the order succeeds and is the player''s first successful deposit',
  fail_reason      VARCHAR(255)  NULL,
  client_ip        VARCHAR(64)   NULL,
  device_id        VARCHAR(128)  NULL,
  created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  paid_at          DATETIME(3)   NULL,
  updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user_created (user_id, created_at),
  KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per player, written in the transaction that marks their first deposit SUCCEEDED.
-- The primary key makes "first deposit" race-free when two deposits succeed concurrently.
CREATE TABLE IF NOT EXISTS player_first_deposit (
  user_id    BIGINT      NOT NULL PRIMARY KEY,
  order_no   VARCHAR(40) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS withdraw_order (
  id               BIGINT        NOT NULL PRIMARY KEY,
  order_no         VARCHAR(40)   NOT NULL,
  user_id          BIGINT        NOT NULL,
  user_line        INT           NOT NULL DEFAULT 1 COMMENT 'player''s user line when the order was created (snapshot)',
  currency         VARCHAR(8)    NOT NULL,
  amount           DECIMAL(20,4) NOT NULL COMMENT 'debited from the player (frozen, then confirmed or unfrozen)',
  fee              DECIMAL(20,4) NOT NULL DEFAULT 0,
  channel_code     VARCHAR(32)   NOT NULL,
  payee_ref        VARCHAR(128)  NOT NULL COMMENT 'token from the payee vault; raw bank / e-wallet numbers are never stored (PII/PCI)',
  status           VARCHAR(16)   NOT NULL COMMENT 'CREATED, FROZEN, PENDING_AUDIT, APPROVED, PAYING, SUCCEEDED, FAILED, REJECTED',
  audit_decision   VARCHAR(16)   NULL COMMENT 'APPROVED, REJECTED; written once',
  audit_reason     VARCHAR(512)  NULL,
  auditor          VARCHAR(64)   NULL COMMENT 'SYSTEM or back-office operator id',
  channel_order_no VARCHAR(64)   NULL,
  fail_reason      VARCHAR(255)  NULL,
  client_ip        VARCHAR(64)   NULL,
  device_id        VARCHAR(128)  NULL,
  created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user_created (user_id, created_at),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mq_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  topic VARCHAR(128) NOT NULL COMMENT 'Kafka topic', msg_key VARCHAR(128) NOT NULL COMMENT 'Kafka message key',
  payload JSON NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending, 1 sent, 2 failed (alert)',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NOT NULL, sent_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_status_next (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Local development seed. The MOCK implementation bean only exists when bingo.payment.mock-channel.enabled=true,
-- so this row is unusable anywhere the mock is disabled.
INSERT IGNORE INTO payment_channel (code, name, direction, status, currencies, min_amount, max_amount, config_ref, sort)
VALUES ('MOCK', 'Mock channel (local dev only)', 'BOTH', 'ENABLED', 'PHP', 100.0000, 50000.0000, NULL, 999);
