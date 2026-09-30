USE bingo_risk;

-- All DATETIME columns hold UTC+8 wall-clock time (the JDBC URL forces the session time zone to +08:00).
-- Status columns hold the Java enum names.

-- One row per withdrawal evaluated by the rules engine. order_no is the idempotency key for redelivered messages.
CREATE TABLE IF NOT EXISTS risk_decision (
  id             BIGINT        NOT NULL PRIMARY KEY,
  order_no       VARCHAR(40)   NOT NULL,
  user_id        BIGINT        NOT NULL,
  user_line      INT           NOT NULL DEFAULT 1 COMMENT 'player''s user line from the withdraw-requested event (snapshot)',
  currency       VARCHAR(8)    NOT NULL,
  amount         DECIMAL(20,4) NOT NULL,
  verdict        VARCHAR(16)   NOT NULL COMMENT 'rules outcome: PASS, REVIEW, REJECT',
  rule_hits      JSON          NOT NULL COMMENT 'non-PASS rule outcomes: [{rule, verdict, reason}]',
  final_decision VARCHAR(16)   NULL COMMENT 'APPROVED / REJECTED sent to payment; NULL while a REVIEW is open',
  final_reason   VARCHAR(512)  NULL,
  auditor        VARCHAR(64)   NULL COMMENT 'SYSTEM or back-office operator id',
  delivered      TINYINT       NOT NULL DEFAULT 0 COMMENT '1 once payment acknowledged final_decision',
  delivered_at   DATETIME(3)   NULL,
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user_created (user_id, created_at),
  KEY idx_delivered_updated (delivered, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS risk_review_task (
  id              BIGINT        NOT NULL PRIMARY KEY,
  order_no        VARCHAR(40)   NOT NULL,
  user_id         BIGINT        NOT NULL,
  user_line       INT           NOT NULL DEFAULT 1 COMMENT 'player''s user line from the withdraw-requested event (snapshot)',
  currency        VARCHAR(8)    NOT NULL,
  amount          DECIMAL(20,4) NOT NULL,
  reasons         VARCHAR(1024) NOT NULL,
  status          VARCHAR(16)   NOT NULL COMMENT 'PENDING, APPROVED, REJECTED',
  operator        VARCHAR(64)   NULL,
  decision_reason VARCHAR(512)  NULL,
  decided_at      DATETIME(3)   NULL,
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Wagering requirements moved to bingo-turnover (10_turnover.sql).

CREATE TABLE IF NOT EXISTS aml_alert (
  id         BIGINT        NOT NULL PRIMARY KEY,
  user_id    BIGINT        NOT NULL,
  user_line  INT           NOT NULL DEFAULT 1 COMMENT 'player''s user line from the event that triggered the alert (snapshot)',
  alert_type VARCHAR(32)   NOT NULL COMMENT 'LARGE_WITHDRAWAL, LARGE_DEPOSIT',
  ref_no     VARCHAR(64)   NOT NULL COMMENT 'order no that triggered the alert',
  amount     DECIMAL(20,4) NOT NULL,
  currency   VARCHAR(8)    NOT NULL,
  detail     JSON          NULL,
  status     VARCHAR(16)   NOT NULL COMMENT 'OPEN, REPORTED, CLOSED',
  created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_type_ref (alert_type, ref_no),
  KEY idx_status_created (status, created_at),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
