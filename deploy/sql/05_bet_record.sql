USE bingo_bet_record;

-- Large, time-partitioned tables. Every unique key includes the partition column (MySQL requirement).
--
-- PARTITION MAINTENANCE: a maintenance job MUST add next month's partitions well ahead of time by splitting
-- p_future, e.g.
--   ALTER TABLE game_round REORGANIZE PARTITION p_future INTO (
--       PARTITION p202701 VALUES LESS THAN ('2027-02-01'),
--       PARTITION p_future VALUES LESS THAN (MAXVALUE));
-- Splitting an EMPTY p_future is instant; once rows land in p_future the reorganize has to copy them, so alert
-- when p_future is not empty. History for reports lives in StarRocks (loaded from Kafka); old partitions are dropped per the
-- record-retention policy of the licence, never deleted row by row.
-- betRecordPartitionJob (MonthlyPartitions) adds the next two months ahead and drops months older than
-- bingo.bet-record.partition-keep-months (default 2, about 60 days of rounds for players).

-- ---------------------------------------------------------------------------------------------------------
-- One player's round at one provider, projected from the wallet ledger. The round identity includes user_id
-- because live-dealer providers share a round id between all players at a table.
-- round_date is the UTC+8 date of the round's first event; lookups by (provider_code, round_id, user_id) always
-- carry a round_date range so they prune to one or two partitions.
-- Amounts are net: bet = bets - rollbacks, payout = payouts - reversals + adjustments.
-- user_line, game_type and game_name are snapshots taken from the round's first event and never updated.
-- No index on user_line (hot insert path): line-scoped reports run on StarRocks.
-- external ids use utf8mb4_bin: provider ids are case-sensitive.
CREATE TABLE IF NOT EXISTS game_round (
    id               BIGINT         NOT NULL COMMENT 'snowflake',
    provider_code    VARCHAR(32)    NOT NULL,
    round_id         VARCHAR(128)   CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    user_id          BIGINT         NOT NULL,
    user_line        INT            NOT NULL DEFAULT 1 COMMENT 'player line when the round started (snapshot)',
    currency         VARCHAR(8)     NOT NULL,
    game_code        VARCHAR(64)    NOT NULL DEFAULT '',
    game_type        VARCHAR(32)    NOT NULL DEFAULT 'OTHER' COMMENT 'GameType from the lobby catalogue when the round started',
    game_name        VARCHAR(255)   NOT NULL DEFAULT '' COMMENT 'catalogue name when the round started (game code if unknown)',
    bet_amount       DECIMAL(20, 4) NOT NULL DEFAULT 0,
    payout_amount    DECIMAL(20, 4) NOT NULL DEFAULT 0,
    bet_count        INT            NOT NULL DEFAULT 0 COMMENT 'live bets: +1 bet, -1 rollback',
    payout_count     INT            NOT NULL DEFAULT 0 COMMENT 'live payouts: +1 payout, -1 reversal',
    status           VARCHAR(16)    NOT NULL COMMENT 'OPEN | SETTLED | CANCELLED',
    round_date       DATE           NOT NULL COMMENT 'partition key: UTC+8 date of the first event',
    first_event_at   DATETIME(3)    NOT NULL,
    last_event_at    DATETIME(3)    NOT NULL,
    settled_at       DATETIME(3)    NULL,
    balance_after    DECIMAL(20, 4) NULL COMMENT 'balance after the wallet txn that closed the round; NULL if none did',
    resolve_attempts INT            NOT NULL DEFAULT 0,
    resolve_outcome  VARCHAR(16)    NULL COMMENT 'last provider resolver outcome',
    event_published  TINYINT        NOT NULL DEFAULT 0 COMMENT 'RoundSettledEvent acknowledged by Kafka',
    created_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id, round_date),
    UNIQUE KEY uk_round (provider_code, round_id, user_id, round_date),
    KEY idx_status_last_event (status, last_event_at),
    KEY idx_user_date (user_id, round_date),
    KEY idx_unpublished (event_published, settled_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'game rounds (ledger projection)'
PARTITION BY RANGE COLUMNS (round_date) (
    PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
    PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
    PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
    PARTITION p202612 VALUES LESS THAN ('2027-01-01'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- ---------------------------------------------------------------------------------------------------------
-- Exactly-once guard for the ledger stream: one row per applied wallet_txn id, inserted in the same local
-- transaction as the game_round update. Snowflake ids are time ordered (bits 22+ = millis since 2025-01-01),
-- so monthly id ranges map to calendar months:
--   boundary(month) = (epoch_ms(month start at UTC+8) - 1735689600000) << 22
-- Partitions older than the Kafka retention + replay window (a txn that old can never be redelivered) are dropped.
CREATE TABLE IF NOT EXISTS round_txn (
    txn_id     BIGINT       NOT NULL COMMENT 'wallet_txn.id',
    round_id   VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (txn_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'applied wallet txn ids'
PARTITION BY RANGE (txn_id) (
    PARTITION p202609 VALUES LESS THAN (231082662297600000), -- 2026-10-01T00:00+08:00
    PARTITION p202610 VALUES LESS THAN (242316686131200000), -- 2026-11-01T00:00+08:00
    PARTITION p202611 VALUES LESS THAN (253188322099200000), -- 2026-12-01T00:00+08:00
    PARTITION p202612 VALUES LESS THAN (264422345932800000), -- 2027-01-01T00:00+08:00
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

-- ---------------------------------------------------------------------------------------------------------
-- Provider bet-history records (the provider's side of reconciliation), pulled by betRecordProviderPullJob.
-- Provider data carries no line: user_line is the player's line when the record was first pulled; it and the
-- catalogue snapshot (game_type, game_name) are kept when the record is re-pulled.
-- No index on user_line (hot insert path): line-scoped reports run on StarRocks.
CREATE TABLE IF NOT EXISTS provider_bet_record (
    id              BIGINT         NOT NULL COMMENT 'snowflake',
    provider_code   VARCHAR(32)    NOT NULL,
    provider_bet_id VARCHAR(128)   CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    round_id        VARCHAR(128)   CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    user_id         BIGINT         NOT NULL,
    user_line       INT            NOT NULL DEFAULT 1 COMMENT 'player line when the record was first pulled (snapshot)',
    currency        VARCHAR(8)     NOT NULL,
    game_code       VARCHAR(64)    NOT NULL DEFAULT '',
    game_type       VARCHAR(32)    NOT NULL DEFAULT 'OTHER' COMMENT 'GameType from the lobby catalogue when first pulled',
    game_name       VARCHAR(255)   NOT NULL DEFAULT '' COMMENT 'catalogue name when first pulled (game code if unknown)',
    bet_amount      DECIMAL(20, 4) NOT NULL,
    payout_amount   DECIMAL(20, 4) NOT NULL DEFAULT 0,
    status          VARCHAR(16)    NOT NULL,
    bet_time        DATETIME(3)    NOT NULL COMMENT 'partition key (UTC+8)',
    settle_time     DATETIME(3)    NULL,
    published       TINYINT        NOT NULL DEFAULT 0 COMMENT 'ProviderBetEvent acknowledged by Kafka',
    created_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id, bet_time),
    UNIQUE KEY uk_provider_bet (provider_code, provider_bet_id, bet_time),
    KEY idx_user_bet_time (user_id, bet_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'provider bet history'
PARTITION BY RANGE COLUMNS (bet_time) (
    PARTITION p202609 VALUES LESS THAN ('2026-10-01 00:00:00'),
    PARTITION p202610 VALUES LESS THAN ('2026-11-01 00:00:00'),
    PARTITION p202611 VALUES LESS THAN ('2026-12-01 00:00:00'),
    PARTITION p202612 VALUES LESS THAN ('2027-01-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- Pull checkpoint per provider: end of the last fully stored and published window (UTC+8).
CREATE TABLE IF NOT EXISTS bet_pull_checkpoint (
    provider_code VARCHAR(32) NOT NULL,
    window_end    DATETIME(3) NOT NULL,
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (provider_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'provider bet pull checkpoints';
