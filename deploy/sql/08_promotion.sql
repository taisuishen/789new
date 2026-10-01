USE bingo_promotion;

-- All DATETIME columns hold UTC+8 wall-clock time (the JDBC URL forces the session time zone to +08:00).
-- stat_date is a calendar day in the platform's business time zone (bingo.promotion.business-zone).
-- Status columns hold the Java enum names.
-- user_line on business rows is the player's line when the row was written (a snapshot, never rewritten).

-- Valid bet per player, currency and provider per business day. Upserted in batches from the round-settled stream.
CREATE TABLE IF NOT EXISTS valid_bet_daily (
  stat_date     DATE          NOT NULL,
  user_id       BIGINT        NOT NULL,
  user_line     INT           NOT NULL DEFAULT 1 COMMENT 'line of the player''s latest round applied to this user-day (not part of the key): a player migrated mid-day ends up on the later line',
  currency      VARCHAR(8)    NOT NULL,
  provider_code VARCHAR(32)   NOT NULL,
  valid_bet     DECIMAL(20,4) NOT NULL DEFAULT 0,
  round_count   INT           NOT NULL DEFAULT 0,
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (stat_date, user_id, currency, provider_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Per-round dedupe for the round-settled stream; written in the same transaction as the upsert.
-- round_key = providerCode:roundId:userId
-- TODO: retention job (or daily RANGE partitions on created_at) deleting rows older than the Kafka retention plus a margin.
CREATE TABLE IF NOT EXISTS promotion_round_applied (
  round_key  VARCHAR(255)  NOT NULL PRIMARY KEY,
  revision   INT           NOT NULL DEFAULT 1 COMMENT 'RoundSettledEvent.revision applied last',
  valid_bet  DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT 'valid bet of that revision, already in valid_bet_daily',
  created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='per-round dedupe of bingo.round.settled; purged after 35 days (promotionRoundAppliedRetentionJob)';

-- Promotions ("活动"): common fields are columns, everything specific to promo_type lives in config_json.
-- user_lines (plural, a set of lines) says which lines' players see and can receive the promotion; it is distinct from
-- the scalar user_line snapshot stored on business rows.
-- A promotion is active while status = ONLINE and start_time <= now < end_time. Several matching promotions: the
-- highest sort wins, then the newest id.
-- config_json is a JSON object. The "display" object is player-facing (returned by GET /api/promotion/activities);
-- every other key is server-side only. Schemas, validated by the internal API (unknown keys are rejected):
--   FIRST_DEPOSIT {"percent":100,"maxAmount":1000,"currencies":["PHP"],
--                  "turnover":{"multiplier":10,"scope":"ALL","scopeValue":null}}
--                 currencies optional (absent = every currency); maxAmount is in the deposit's currency
--   REBATE        {"defaultRate":0.005,"defaultDailyCap":null,"providers":{"DEMO":{"rate":0.008,"dailyCap":100}},
--                  "turnover":{"multiplier":1}}
--                 per-provider rate and daily cap, else the defaults; no defaultRate = unlisted providers earn
--                 nothing; rate 0 excludes a provider; a null cap = no cap
--   turnover.scope: ALL (default, no scopeValue), GAME_TYPE (scopeValue a game type, e.g. "SLOT"),
--                   GAME (scopeValue "PROVIDER:GAME_CODE")
-- MySQL keeps JSON numbers with a fraction as doubles, so config numbers are limited to 15 significant digits.
-- TODO: four-eyes approval and audit log for promotion changes (the version column already guards stale edits).
CREATE TABLE IF NOT EXISTS promotion (
  id          BIGINT       NOT NULL PRIMARY KEY COMMENT 'snowflake, the activity id',
  name        VARCHAR(128) NOT NULL,
  promo_type  VARCHAR(32)  NOT NULL COMMENT 'FIRST_DEPOSIT, REBATE ...; decides the schema of config_json',
  user_lines  JSON         NOT NULL COMMENT 'array of lines whose players see the promotion, e.g. [1] or [1,2]',
  start_time  DATETIME(3)  NOT NULL COMMENT 'inclusive',
  end_time    DATETIME(3)  NOT NULL COMMENT 'exclusive',
  status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, ONLINE, OFFLINE',
  sort        INT          NOT NULL DEFAULT 0 COMMENT 'higher first',
  config_json JSON         NOT NULL,
  version     INT          NOT NULL DEFAULT 1 COMMENT 'optimistic lock, bumped by every change',
  created_by  VARCHAR(64)  NOT NULL,
  updated_by  VARCHAR(64)  NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_status_end (status, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One rebate per (statDate, user, currency), computed with the terms of one REBATE promotion; the wallet bizNo is
-- "REBATE-" + id. The terms needed to pay it later (wagering) are copied here, so editing the promotion afterwards
-- never changes an earned rebate.
CREATE TABLE IF NOT EXISTS rebate_record (
  id                   BIGINT        NOT NULL PRIMARY KEY,
  stat_date            DATE          NOT NULL,
  user_id              BIGINT        NOT NULL,
  user_line            INT           NOT NULL DEFAULT 1 COMMENT 'player''s line at the end of the business day (the most recently updated valid_bet_daily row)',
  currency             VARCHAR(8)    NOT NULL,
  valid_bet            DECIMAL(20,4) NOT NULL,
  amount               DECIMAL(20,4) NOT NULL,
  promotion_id         BIGINT        NOT NULL COMMENT 'REBATE promotion whose terms computed this row',
  turnover_multiplier  DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT 'wagering requirement = amount x multiplier (0 = none)',
  turnover_scope       VARCHAR(16)   NULL COMMENT 'ALL, GAME_TYPE, GAME; NULL = ALL',
  turnover_scope_value VARCHAR(128)  NULL COMMENT 'GAME_TYPE: game type; GAME: PROVIDER:GAME_CODE',
  status               VARCHAR(16)   NOT NULL COMMENT 'PENDING, PAID, FAILED',
  fail_reason          VARCHAR(255)  NULL,
  paid_at              DATETIME(3)   NULL,
  created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_date_user_currency (stat_date, user_id, currency),
  KEY idx_status_id (status, id),
  KEY idx_user_date (user_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bonus_grant (
  id                   BIGINT        NOT NULL PRIMARY KEY,
  biz_no               VARCHAR(64)   NOT NULL COMMENT 'wallet bizNo, e.g. FDB-{depositOrderNo}',
  user_id              BIGINT        NOT NULL,
  user_line            INT           NOT NULL DEFAULT 1 COMMENT 'player''s line from the triggering event (deposit), snapshot',
  currency             VARCHAR(8)    NOT NULL,
  amount               DECIMAL(20,4) NOT NULL,
  bonus_type           VARCHAR(32)   NOT NULL COMMENT 'FIRST_DEPOSIT, CAMPAIGN ...',
  promotion_id         BIGINT        NULL COMMENT 'promotion whose terms computed the grant (attribution)',
  status               VARCHAR(16)   NOT NULL COMMENT 'PENDING, PAID, FAILED',
  turnover_multiplier  DECIMAL(10,4) NOT NULL DEFAULT 0,
  turnover_scope       VARCHAR(16)   NULL COMMENT 'ALL, GAME_TYPE, GAME; NULL = ALL',
  turnover_scope_value VARCHAR(128)  NULL COMMENT 'GAME_TYPE: game type; GAME: PROVIDER:GAME_CODE',
  fail_reason          VARCHAR(255)  NULL,
  created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  paid_at              DATETIME(3)   NULL,
  updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_biz_no (biz_no),
  KEY idx_status_created (status, created_at),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mq_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  topic VARCHAR(128) NOT NULL COMMENT 'Kafka topic', msg_key VARCHAR(128) NOT NULL COMMENT 'Kafka message key',
  payload JSON NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending (retried until sent), 1 sent (purged after 7 days)',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NOT NULL, sent_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_status_next (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Example rebate programme for line 1 (0.5% of valid bet on every provider, no cap, wagering 1x), seeded DRAFT:
-- rebates stay off until the programme and its rates are approved and it is put ONLINE through the internal API.
INSERT IGNORE INTO promotion (id, name, promo_type, user_lines, start_time, end_time, status, sort, config_json, created_by, updated_by)
VALUES (1, 'Daily rebate', 'REBATE', '[1]', '2026-01-01 00:00:00.000', '2100-01-01 00:00:00.000', 'DRAFT', 0,
        '{"defaultRate":0.005,"defaultDailyCap":null,"providers":{},"turnover":{"multiplier":1,"scope":"ALL"}}',
        'system', 'system');
