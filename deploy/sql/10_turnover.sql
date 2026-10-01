USE bingo_turnover;

-- Wagering requirements ("稽核"). Sharded by user_id exactly like the wallet (same 1024 logical shards, see
-- bingo-turnover-service application-sharding.yml): every statement belongs to one player and runs on that
-- player's shard. Buckets and records change in one local transaction per round, in real time like the balance. turnover_setting is small configuration and lives on the global datasource (ds00).
-- All DATETIME columns hold UTC+8 wall-clock time. Status / type columns hold the Java enum names.

-- One requirement ("bucket"); a player may hold many at once. A settled round's valid bet is consumed from the
-- narrowest scope to the widest (scope_rank 1 GAME -> 2 GAME_TYPE -> 3 ALL), oldest bucket first within a rank,
-- and only by buckets created before the round's first bet. Turnover beyond all buckets is not carried forward.
CREATE TABLE IF NOT EXISTS turnover_bucket (
    id              BIGINT        NOT NULL COMMENT 'snowflake',
    user_id         BIGINT        NOT NULL,
    user_line       INT           NOT NULL DEFAULT 1 COMMENT 'player''s line when the bucket was created',
    currency        VARCHAR(8)    NOT NULL,
    scope_type      VARCHAR(16)   NOT NULL COMMENT 'GAME / GAME_TYPE / ALL',
    scope_value     VARCHAR(128)  NOT NULL DEFAULT '' COMMENT 'GAME: PROVIDER:GAME_CODE; GAME_TYPE: GameType name; ALL: empty',
    scope_rank      TINYINT       NOT NULL COMMENT '1 GAME, 2 GAME_TYPE, 3 ALL: consumption order',
    source_type     VARCHAR(16)   NOT NULL COMMENT 'DEPOSIT / BONUS / REBATE / MANUAL',
    source_no       VARCHAR(64)   NOT NULL COMMENT 'deposit order no / bonus biz no / back-office ticket; idempotency key',
    base_amount     DECIMAL(20,4) NOT NULL COMMENT 'deposit or bonus amount the requirement derives from (= required for MANUAL)',
    multiplier      DECIMAL(10,4) NOT NULL,
    required_amount DECIMAL(20,4) NOT NULL,
    achieved_amount DECIMAL(20,4) NOT NULL DEFAULT 0,
    status          VARCHAR(16)   NOT NULL COMMENT 'ACTIVE / COMPLETED / CLEARED / VOID',
    close_reason    VARCHAR(32)   NULL COMMENT 'FULFILLED / REMAINING_BELOW_THRESHOLD / BALANCE_BELOW_THRESHOLD / MANUAL',
    closed_by       VARCHAR(64)   NULL COMMENT 'SYSTEM or back-office operator',
    version         INT           NOT NULL DEFAULT 0 COMMENT 'optimistic lock; every progress / close bumps it',
    created_at      DATETIME(3)   NOT NULL COMMENT 'time of the source event (deposit / grant), not of the insert',
    closed_at       DATETIME(3)   NULL,
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_source (user_id, source_type, source_no),
    KEY idx_user_active (user_id, status, currency, scope_rank, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'wagering requirement buckets';

-- 稽核记录: append-only history of every bucket change, written in the same transaction as the change, in real time
-- (per round, like the balance; never aggregated or batched):
--   CREATE  the bucket was created                   amount = required turnover
--   WAGER   a settled round's valid bet was taken    amount = valid bet taken; one row per bucket the round reached
--   CLEAR   the bucket was cleared                   amount = remainder dropped; reason BALANCE_BELOW_THRESHOLD / MANUAL
-- The WAGER that fulfils a bucket has status_after = COMPLETED (reason FULFILLED or REMAINING_BELOW_THRESHOLD).
-- Exactly-once per round: seq is the bucket's position in that round's waterfall, so a redelivered round always
-- collides on (round_key, 0) whichever buckets it would reach now; rounds that reach no bucket leave no row.
-- round_key = providerCode:roundId:userId (NULL for CREATE / CLEAR, which unique keys ignore).
-- record_date is the partition key: for WAGER the UTC+8 date of the round's first bet (a redelivery lands in the same
-- partition, so the unique key still catches it), otherwise the UTC+8 date of the change.
-- PARTITION MAINTENANCE: turnoverPartitionJob (MonthlyPartitions): next two months ahead, months older than
-- bingo.turnover.record-keep-months (default 6) dropped.
CREATE TABLE IF NOT EXISTS turnover_record (
    id              BIGINT        NOT NULL COMMENT 'snowflake',
    user_id         BIGINT        NOT NULL,
    user_line       INT           NOT NULL DEFAULT 1 COMMENT 'player''s line at the change',
    bucket_id       BIGINT        NOT NULL,
    record_type     VARCHAR(16)   NOT NULL COMMENT 'CREATE / WAGER / CLEAR',
    currency        VARCHAR(8)    NOT NULL,
    amount          DECIMAL(20,4) NOT NULL COMMENT 'WAGER: valid bet taken; negative = taken back after a round revision',
    achieved_after  DECIMAL(20,4) NOT NULL,
    remaining_after DECIMAL(20,4) NOT NULL COMMENT 'turnover still required after the change (0 once closed)',
    status_after    VARCHAR(16)   NOT NULL COMMENT 'bucket status after the change',
    reason          VARCHAR(32)   NULL COMMENT 'CREATE: source type; closing WAGER / CLEAR: close reason',
    operator        VARCHAR(64)   NULL COMMENT 'SYSTEM or back-office operator',
    round_key       VARCHAR(255)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT 'WAGER only',
    round_revision  INT           NULL COMMENT 'WAGER only: RoundSettledEvent.revision that produced the row (1 = first settlement)',
    seq             TINYINT       NULL COMMENT 'WAGER only: position in the round''s waterfall (0 = first bucket reached)',
    provider_code   VARCHAR(32)   NULL COMMENT 'WAGER only',
    game_code       VARCHAR(64)   NULL COMMENT 'WAGER only',
    game_type       VARCHAR(32)   NULL COMMENT 'WAGER only',
    record_date     DATE          NOT NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id, record_date),
    UNIQUE KEY uk_round_seq (round_key, round_revision, seq, record_date),
    KEY idx_bucket (bucket_id, id),
    KEY idx_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'wagering requirement records'
PARTITION BY RANGE COLUMNS (record_date) (
    PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
    PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
    PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
    PARTITION p202612 VALUES LESS THAN ('2027-01-01'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- Back-office rules per user line and currency (global datasource). A missing row means: no automatic clearing,
-- deposit multiplier = bingo.turnover.default-deposit-multiplier.
CREATE TABLE IF NOT EXISTS turnover_setting (
    user_line                INT           NOT NULL,
    currency                 VARCHAR(8)    NOT NULL,
    clear_below_balance      DECIMAL(20,4) NULL COMMENT 'balance after a settled round below this -> clear every ACTIVE bucket of the currency; NULL = off',
    complete_below_remaining DECIMAL(20,4) NULL COMMENT 'remaining requirement below this -> bucket counts as fulfilled; NULL = off',
    deposit_multiplier       DECIMAL(10,4) NOT NULL DEFAULT 1 COMMENT 'deposit x multiplier = ALL-games bucket per deposit; 0 = none',
    updated_by               VARCHAR(64)   NOT NULL,
    updated_at               DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_line, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'wagering rules per line';
