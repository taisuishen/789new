USE bingo_reconcile;

-- All aggregates here are built asynchronously, in batches, from Kafka (bingo.wallet.txn, bingo.provider.bet).
-- Platform totals are never maintained per wallet transaction: a shared total row would be a global hot row.
-- stat_hour / period_start are UTC+8.
-- user_line: aggregates are kept per player line (the line carried by each event, a snapshot at write time) for
-- line-scoped reports. Every platform-vs-provider comparison, settlement and RTP check sums all lines, so
-- recon_diff, provider_settlement and rtp_alert have no line.

-- Consumed offsets, advanced in the same local transaction as the aggregates (exactly-once effect).
CREATE TABLE IF NOT EXISTS kafka_offset (
    consumer_group VARCHAR(128) NOT NULL,
    topic          VARCHAR(255) NOT NULL,
    partition_no   INT          NOT NULL,
    next_offset    BIGINT       NOT NULL COMMENT 'next offset to apply',
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group, topic, partition_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'stored Kafka offsets';

-- Ledger side: game transactions per hour of the txn's createdAt.
-- net bet = bet - rollback; net payout = payout - payout_reversal + adjust (adjust is signed, + = credit).
CREATE TABLE IF NOT EXISTS recon_platform_hourly (
    stat_hour              DATETIME       NOT NULL,
    user_line              INT            NOT NULL DEFAULT 1 COMMENT 'player line at txn time (WalletTxnEvent.userLine)',
    provider_code          VARCHAR(32)    NOT NULL,
    game_code              VARCHAR(64)    NOT NULL DEFAULT '',
    currency               VARCHAR(8)     NOT NULL,
    bet_amount             DECIMAL(20, 4) NOT NULL DEFAULT 0,
    rollback_amount        DECIMAL(20, 4) NOT NULL DEFAULT 0,
    payout_amount          DECIMAL(20, 4) NOT NULL DEFAULT 0,
    payout_reversal_amount DECIMAL(20, 4) NOT NULL DEFAULT 0,
    adjust_amount          DECIMAL(20, 4) NOT NULL DEFAULT 0,
    bet_count              BIGINT         NOT NULL DEFAULT 0 COMMENT 'BET transactions (stakes)',
    txn_count              BIGINT         NOT NULL DEFAULT 0,
    updated_at             DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (stat_hour, user_line, provider_code, game_code, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'ledger aggregates per hour';

-- Provider side: provider bet-history records per hour of the provider's bet time.
CREATE TABLE IF NOT EXISTS recon_provider_hourly (
    stat_hour     DATETIME       NOT NULL,
    user_line     INT            NOT NULL DEFAULT 1 COMMENT 'player line at first pull (ProviderBetEvent.userLine)',
    provider_code VARCHAR(32)    NOT NULL,
    game_code     VARCHAR(64)    NOT NULL DEFAULT '',
    currency      VARCHAR(8)     NOT NULL,
    bet_amount    DECIMAL(20, 4) NOT NULL DEFAULT 0,
    payout_amount DECIMAL(20, 4) NOT NULL DEFAULT 0,
    record_count  BIGINT         NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (stat_hour, user_line, provider_code, game_code, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'provider aggregates per hour';

-- Differences / tickets. diff = platform_value - provider_value.
-- OPEN / AUTO_FIXED are automation-controlled (re-runs update them), RESOLVED / IGNORED are operator decisions.
CREATE TABLE IF NOT EXISTS recon_diff (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    level          VARCHAR(16)    NOT NULL COMMENT 'HOURLY | DAILY',
    provider_code  VARCHAR(32)    NOT NULL,
    currency       VARCHAR(8)     NOT NULL,
    period_start   DATETIME       NOT NULL,
    metric         VARCHAR(64)    NOT NULL COMMENT 'NET_BET, NET_PAYOUT or a per-record mismatch type',
    platform_value DECIMAL(20, 4) NOT NULL,
    provider_value DECIMAL(20, 4) NOT NULL,
    diff           DECIMAL(20, 4) NOT NULL,
    status         VARCHAR(16)    NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN | AUTO_FIXED | RESOLVED | IGNORED',
    note           VARCHAR(500)   NULL,
    resolved_at    DATETIME(3)    NULL,
    created_at     DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_diff (level, provider_code, currency, period_start, metric),
    KEY idx_status (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'reconciliation differences';

-- GGR per reporting day (bingo.reconcile.report-zone), rebuilt from recon_platform_hourly by reconDailyGgrJob.
CREATE TABLE IF NOT EXISTS ggr_daily (
    stat_date     DATE           NOT NULL,
    user_line     INT            NOT NULL DEFAULT 1 COMMENT 'from recon_platform_hourly.user_line',
    provider_code VARCHAR(32)    NOT NULL,
    game_code     VARCHAR(64)    NOT NULL DEFAULT '',
    currency      VARCHAR(8)     NOT NULL,
    bet           DECIMAL(20, 4) NOT NULL COMMENT 'net bet',
    payout        DECIMAL(20, 4) NOT NULL COMMENT 'net payout',
    ggr           DECIMAL(20, 4) NOT NULL COMMENT 'bet - payout',
    bet_count     BIGINT         NOT NULL DEFAULT 0 COMMENT 'stakes; approximates rounds for the RTP monitor',
    created_at    DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (stat_date, user_line, provider_code, game_code, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'daily GGR';

-- Monthly revenue-share statements. DRAFT is recomputed by reconProviderSettlementJob; CONFIRMED is frozen.
CREATE TABLE IF NOT EXISTS provider_settlement (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    period             CHAR(7)        NOT NULL COMMENT 'yyyy-MM (reporting zone)',
    provider_code      VARCHAR(32)    NOT NULL,
    currency           VARCHAR(8)     NOT NULL,
    ggr                DECIMAL(20, 4) NOT NULL,
    revenue_share_rate DECIMAL(6, 4)  NOT NULL COMMENT '0.1200 = 12%',
    amount_due         DECIMAL(20, 4) NOT NULL,
    status             VARCHAR(16)    NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | CONFIRMED',
    created_at         DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement (period, provider_code, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'provider revenue-share settlements';

-- Games whose actual RTP deviates from the certified theoretical RTP over the monitoring window (percent).
CREATE TABLE IF NOT EXISTS rtp_alert (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    stat_date       DATE           NOT NULL COMMENT 'last reporting day of the window',
    provider_code   VARCHAR(32)    NOT NULL,
    game_code       VARCHAR(64)    NOT NULL,
    currency        VARCHAR(8)     NOT NULL,
    window_days     INT            NOT NULL,
    actual_rtp      DECIMAL(9, 3)  NOT NULL,
    theoretical_rtp DECIMAL(6, 3)  NOT NULL,
    bet             DECIMAL(20, 4) NOT NULL,
    payout          DECIMAL(20, 4) NOT NULL,
    rounds          BIGINT         NOT NULL,
    created_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rtp_alert (stat_date, provider_code, game_code, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'RTP deviation alerts';
