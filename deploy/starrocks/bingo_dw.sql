-- =====================================================================================================
-- Reporting / reconciliation / risk analytics on StarRocks (Huawei CloudTable StarRocks), fed ONLY from the Kafka
-- topics that already exist (Routine Load). Player-facing queries never come here: they read the TaurusDB
-- read-only nodes of their shard (retention window).
--
--   bingo.wallet.txn     -> wallet_txn     (ledger; INSERT-only, the CDC job filters row_kind = '+I')
--   bingo.round.settled  -> game_round     (settled / cancelled rounds, republished at least once)
--   bingo.provider.bet   -> provider_bet   (provider bet history, re-pulled records overwrite)
-- Primary Key tables: redeliveries overwrite the same row, so sums stay exact.
-- Dimensions (players, agents, lines, deposits, promotions) are read live through a JDBC catalog, not copied.
--
-- Times: every *_at column is UTC+8 wall-clock time (platform convention). The ledger events carry
-- "2026-10-01T16:00:00.123+08:00" (Flink CDC job); the round / provider events carry Instants in UTC
-- ("2026-10-01T08:00:00.123Z", Jackson), converted with convert_tz below.
-- Replace the __PLACEHOLDERS__; keep passwords out of the repository (set them when running the script).
-- verify: syntax against the StarRocks version of the CloudTable cluster (written for 3.x).
-- =====================================================================================================

CREATE DATABASE IF NOT EXISTS bingo_dw;
USE bingo_dw;

-- ---------------------------------------------------------------------------------------------------- tables
CREATE TABLE IF NOT EXISTS wallet_txn (
    id              BIGINT         NOT NULL COMMENT 'wallet_txn.id (snowflake)',
    created_at      DATETIME       NOT NULL COMMENT 'UTC+8',
    user_id         BIGINT         NOT NULL,
    user_line       INT            NOT NULL,
    currency        VARCHAR(8)     NOT NULL,
    txn_type        VARCHAR(24)    NOT NULL,
    direction       TINYINT        NOT NULL COMMENT '-1 debit, 1 credit, 0 none',
    amount          DECIMAL(20, 4) NOT NULL,
    balance_after   DECIMAL(20, 4) NOT NULL,
    provider_code   VARCHAR(32)    NOT NULL,
    provider_txn_id VARCHAR(128)   NOT NULL,
    round_id        VARCHAR(128)   NULL,
    game_code       VARCHAR(64)    NULL,
    ref_txn_id      VARCHAR(128)   NULL,
    round_closed    BOOLEAN        NOT NULL,
    status          TINYINT        NOT NULL COMMENT '1 normal, 3 tombstone'
)
PRIMARY KEY (id, created_at)
PARTITION BY date_trunc('day', created_at)
DISTRIBUTED BY HASH (id)
ORDER BY (user_id, created_at)
PROPERTIES (
    "replication_num" = "3",
    -- retention of the daily partitions per the licence's record-keeping period (verify the property name for
    -- the cluster's StarRocks version: partition_live_number / partition_ttl)
    "partition_live_number" = "400"
);

CREATE TABLE IF NOT EXISTS game_round (
    settled_at    DATETIME       NOT NULL COMMENT 'UTC+8',
    provider_code VARCHAR(32)    NOT NULL,
    round_id      VARCHAR(128)   NOT NULL,
    user_id       BIGINT         NOT NULL,
    user_line     INT            NOT NULL,
    currency      VARCHAR(8)     NOT NULL,
    game_code     VARCHAR(64)    NOT NULL,
    game_type     VARCHAR(32)    NOT NULL,
    game_name     VARCHAR(255)   NOT NULL,
    bet_amount    DECIMAL(20, 4) NOT NULL,
    payout_amount DECIMAL(20, 4) NOT NULL,
    valid_bet     DECIMAL(20, 4) NOT NULL,
    status        VARCHAR(16)    NOT NULL COMMENT 'SETTLED / CANCELLED',
    bet_at        DATETIME       NULL COMMENT 'UTC+8, first bet of the round'
)
PRIMARY KEY (settled_at, provider_code, round_id, user_id)
PARTITION BY date_trunc('day', settled_at)
DISTRIBUTED BY HASH (provider_code, round_id)
ORDER BY (user_id, settled_at)
PROPERTIES ("replication_num" = "3", "partition_live_number" = "400");

CREATE TABLE IF NOT EXISTS provider_bet (
    bet_at          DATETIME       NOT NULL COMMENT 'UTC+8',
    provider_code   VARCHAR(32)    NOT NULL,
    provider_bet_id VARCHAR(128)   NOT NULL,
    round_id        VARCHAR(128)   NULL,
    user_id         BIGINT         NOT NULL,
    user_line       INT            NOT NULL,
    currency        VARCHAR(8)     NOT NULL,
    game_code       VARCHAR(64)    NOT NULL,
    bet_amount      DECIMAL(20, 4) NOT NULL,
    payout_amount   DECIMAL(20, 4) NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    settled_at      DATETIME       NULL COMMENT 'UTC+8'
)
PRIMARY KEY (bet_at, provider_code, provider_bet_id)
PARTITION BY date_trunc('day', bet_at)
DISTRIBUTED BY HASH (provider_code, provider_bet_id)
PROPERTIES ("replication_num" = "3", "partition_live_number" = "400");

-- ------------------------------------------------------------------------------------------ Kafka access
-- DMS for Kafka over SASL_SSL: a dedicated read-only DMS user; the DMS CA certificate uploaded once with
--   CREATE FILE "dms_ca.pem" IN bingo_dw PROPERTIES ("url" = "__URL_OF_THE_PEM__", "catalog" = "kafka");
-- Every job below uses its own consumer group (set by StarRocks), independent of the services' groups.

CREATE ROUTINE LOAD bingo_dw.load_wallet_txn ON wallet_txn
COLUMNS (id, user_id, user_line, currency, txn_type, direction, amount, balance_after, provider_code,
         provider_txn_id, round_id, game_code, ref_txn_id, round_closed, status, created_raw,
         created_at = str_to_date(left(replace(created_raw, 'T', ' '), 19), '%Y-%m-%d %H:%i:%s'))
PROPERTIES (
    "format" = "json",
    "jsonpaths" = "[\"$.id\",\"$.userId\",\"$.userLine\",\"$.currency\",\"$.txnType\",\"$.direction\",\"$.amount\",\"$.balanceAfter\",\"$.providerCode\",\"$.providerTxnId\",\"$.roundId\",\"$.gameCode\",\"$.refTxnId\",\"$.roundClosed\",\"$.status\",\"$.createdAt\"]",
    "desired_concurrent_number" = "16",
    "max_error_number" = "0"
)
FROM KAFKA (
    "kafka_broker_list" = "__DMS_KAFKA_SASL_BROKERS__",
    "kafka_topic" = "bingo.wallet.txn",
    "property.kafka_default_offsets" = "OFFSET_BEGINNING",
    "property.security.protocol" = "SASL_SSL",
    "property.sasl.mechanism" = "SCRAM-SHA-512",
    "property.sasl.username" = "__DMS_READER_USER__",
    "property.sasl.password" = "__DMS_READER_PASSWORD__",
    "property.ssl.ca.location" = "FILE:dms_ca.pem"
);

CREATE ROUTINE LOAD bingo_dw.load_game_round ON game_round
COLUMNS (provider_code, round_id, user_id, user_line, currency, game_code, game_type, game_name, bet_amount,
         payout_amount, valid_bet, status, bet_raw, settled_raw,
         bet_at = convert_tz(str_to_date(left(replace(bet_raw, 'T', ' '), 19), '%Y-%m-%d %H:%i:%s'), '+00:00', '+08:00'),
         settled_at = convert_tz(str_to_date(left(replace(settled_raw, 'T', ' '), 19), '%Y-%m-%d %H:%i:%s'), '+00:00', '+08:00'))
PROPERTIES (
    "format" = "json",
    "jsonpaths" = "[\"$.providerCode\",\"$.roundId\",\"$.userId\",\"$.userLine\",\"$.currency\",\"$.gameCode\",\"$.gameType\",\"$.gameName\",\"$.betAmount\",\"$.payoutAmount\",\"$.validBet\",\"$.status\",\"$.betTime\",\"$.settledTime\"]",
    "desired_concurrent_number" = "8",
    "max_error_number" = "0"
)
FROM KAFKA (
    "kafka_broker_list" = "__DMS_KAFKA_SASL_BROKERS__",
    "kafka_topic" = "bingo.round.settled",
    "property.kafka_default_offsets" = "OFFSET_BEGINNING",
    "property.security.protocol" = "SASL_SSL",
    "property.sasl.mechanism" = "SCRAM-SHA-512",
    "property.sasl.username" = "__DMS_READER_USER__",
    "property.sasl.password" = "__DMS_READER_PASSWORD__",
    "property.ssl.ca.location" = "FILE:dms_ca.pem"
);

CREATE ROUTINE LOAD bingo_dw.load_provider_bet ON provider_bet
COLUMNS (provider_code, provider_bet_id, round_id, user_id, user_line, currency, game_code, bet_amount,
         payout_amount, status, bet_raw, settled_raw,
         bet_at = convert_tz(str_to_date(left(replace(bet_raw, 'T', ' '), 19), '%Y-%m-%d %H:%i:%s'), '+00:00', '+08:00'),
         settled_at = convert_tz(str_to_date(left(replace(settled_raw, 'T', ' '), 19), '%Y-%m-%d %H:%i:%s'), '+00:00', '+08:00'))
PROPERTIES (
    "format" = "json",
    "jsonpaths" = "[\"$.providerCode\",\"$.providerBetId\",\"$.roundId\",\"$.userId\",\"$.userLine\",\"$.currency\",\"$.gameCode\",\"$.betAmount\",\"$.payoutAmount\",\"$.status\",\"$.betTime\",\"$.settleTime\"]",
    "desired_concurrent_number" = "2",
    "max_error_number" = "0"
)
FROM KAFKA (
    "kafka_broker_list" = "__DMS_KAFKA_SASL_BROKERS__",
    "kafka_topic" = "bingo.provider.bet",
    "property.kafka_default_offsets" = "OFFSET_BEGINNING",
    "property.security.protocol" = "SASL_SSL",
    "property.sasl.mechanism" = "SCRAM-SHA-512",
    "property.sasl.username" = "__DMS_READER_USER__",
    "property.sasl.password" = "__DMS_READER_PASSWORD__",
    "property.ssl.ca.location" = "FILE:dms_ca.pem"
);

-- ------------------------------------------------------------------------------------ dimensions (live)
-- The shared TaurusDB instance (bingo_user, bingo_payment, bingo_promotion, ...) through its READ-ONLY node,
-- with a read-only account. Low-volume tables are joined live instead of being copied.
CREATE EXTERNAL CATALOG IF NOT EXISTS taurus_shared PROPERTIES (
    "type" = "jdbc",
    "user" = "__REPORT_RO_USER__",
    "password" = "__REPORT_RO_PASSWORD__",
    "jdbc_uri" = "jdbc:mysql://__TAURUSDB_SHARED_READONLY_HOST__:3306",
    "driver_url" = "__URL_OF_mysql-connector-j.jar__",
    "driver_class" = "com.mysql.cj.jdbc.Driver"
);

-- ---------------------------------------------------------------------------------------- example reports
-- GGR per line and day:
--   SELECT DATE(settled_at) AS day, user_line, currency, SUM(bet_amount) AS bet, SUM(payout_amount) AS payout,
--          SUM(bet_amount) - SUM(payout_amount) AS ggr
--     FROM bingo_dw.game_round WHERE status = 'SETTLED' AND settled_at >= '2026-10-01' AND settled_at < '2026-11-01'
--    GROUP BY 1, 2, 3;
-- Valid bet per upline agent (a viewer limited to lines 1 and 2):
--   SELECT u.parent_agent_id, u.parent_agent_name, SUM(r.valid_bet) AS valid_bet
--     FROM bingo_dw.game_round r JOIN taurus_shared.bingo_user.user_account u ON u.id = r.user_id
--    WHERE r.user_line IN (1, 2) AND r.settled_at >= '2026-10-01' AND r.settled_at < '2026-10-02'
--    GROUP BY 1, 2;
