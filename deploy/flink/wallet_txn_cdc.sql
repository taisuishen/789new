-- =====================================================================================================
-- wallet_txn (TaurusDB, databases bingo_wallet_00..15)  --MySQL CDC-->  Kafka topic bingo.wallet.txn
--
-- Sharded wallet (bingo.shard.*): run ONE job per TaurusDB INSTANCE; it captures every wallet database on that
-- instance ('database-name' is a regex). Each job has its own __WALLET_DB_HOST__ and a non-overlapping 'server-id'
-- range (e.g. instance NN -> 54NN1-54NN8). A user lives in exactly one database, so per-user ordering is preserved;
-- all jobs write to the same topic, keyed by userId.
-- After a database moves to another instance (application-sharding.yml of the wallet, steps 3-4), start the job on
-- the new instance with 'scan.startup.mode' = 'timestamp' and 'scan.startup.timestamp-millis' = T_sync: the moment,
-- during the write freeze, when DRS had applied everything AND the old instance's job had emitted everything. Rows
-- copied by DRS are then not emitted again, and no player's events from the two jobs interleave.
-- Size bingo.wallet.txn with 192 partitions (~250k events/s at peak).
--
-- Record key   : userId as a raw UTF-8 string (same bytes as a Java StringSerializer key, so Kafka's default
--                partitioner puts all events of a user on one partition, in commit order).
-- Record value : JSON matching the Java record WalletTxnEvent, exactly these camelCase fields:
--                id, userId, userLine, currency, txnType, direction, amount, balanceAfter, providerCode, providerTxnId,
--                roundId, gameCode, refTxnId, roundClosed, status, createdAt
--                (createdAt = ISO-8601 string with the platform offset, e.g. 2026-09-30T16:00:00.123+08:00
--                 -> java.time.Instant). The platform runs on UTC+8 everywhere, including DATETIME columns.
--
-- wallet_txn is append-only for the business (rows are never updated), so the source is read as an append-only
-- stream ('scan.read-changelog-as-append-only.enabled', Flink CDC >= 3.4) and written to a plain, insert-only Kafka
-- sink. Only real INSERTs become events (row_kind = '+I'): the retention job's DELETEs of rows older than the
-- idempotency window (and any accidental UPDATE) never reach the topic, so they cannot re-emit old transactions.
-- Consumers still de-duplicate by id (the sink is at-least-once).
--
-- Runtime: Flink 1.19/1.20 + flink-sql-connector-mysql-cdc-3.5.x.jar + flink-sql-connector-kafka-<ver>-1.20.jar,
--   self-managed (e.g. Flink Kubernetes Operator on CCE):  ./bin/sql-client.sh -f wallet_txn_cdc.sql
--   or Huawei DLI "Flink OpenSource SQL" (verify that the DLI Flink version ships a MySQL CDC connector that
--   supports scan.read-changelog-as-append-only.enabled; otherwise run it self-managed).
-- Replace the __PLACEHOLDERS__ (the SQL client does not substitute environment variables). Keep the password
-- out of the repository (DLI: datasource authentication / DEW; self-managed: inject at submit time).
--
-- Database prerequisites (TaurusDB parameter template / MySQL):
--   binlog enabled, binlog_format = ROW, binlog_row_image = FULL,
--   binlog retention longer than the longest possible job outage (restart from a checkpoint needs the binlog).
--   CDC account, read-only on the wallet schema plus replication privileges:
--     CREATE USER 'bingo_cdc'@'%' IDENTIFIED BY '<from DEW>';
--     GRANT SELECT ON `bingo\_wallet\_%`.* TO 'bingo_cdc'@'%';
--     GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'bingo_cdc'@'%';
--     -- (the Flink CDC docs also list SHOW DATABASES; add it if the job fails while discovering tables)
--   Flink CDC documents MySQL 5.6-8.0 (TaurusDB is 8.0-compatible). The local mysql:8.4 container may not work
--   because 8.4 removed SHOW MASTER STATUS; locally, the wallet publishes events after commit instead
--   (WALLET_AFTER_COMMIT_PUBLISH=true). Never enable both in the same environment.
-- =====================================================================================================

SET 'pipeline.name' = 'bingo-wallet-txn-cdc';
-- Checkpoints drive the CDC snapshot->binlog switch and the Kafka flush. Configure state.checkpoints.dir
-- (OBS / PVC) and keep externalized checkpoints so the job resumes from its binlog position.
SET 'execution.checkpointing.interval' = '10s';
SET 'execution.checkpointing.externalized-checkpoint-retention' = 'RETAIN_ON_CANCELLATION';
SET 'table.local-time-zone' = 'Asia/Manila';   -- UTC+8, no DST
-- Same parallelism everywhere so source and sink chain (no rebalance): the binlog is read by a single reader,
-- and chaining keeps each user's events in commit order all the way to Kafka.
SET 'parallelism.default' = '2';

CREATE TABLE wallet_txn_src (
    id               BIGINT,
    user_id          BIGINT,
    user_line        INT,            -- player's line when the row was written
    currency         STRING,
    txn_type         STRING,
    direction        TINYINT,
    amount           DECIMAL(20, 4),
    balance_after    DECIMAL(20, 4),
    provider_code    STRING,
    provider_txn_id  STRING,
    round_id         STRING,
    game_code        STRING,
    ref_txn_id       STRING,
    round_closed     TINYINT,        -- if the column is ever declared TINYINT(1)/BOOLEAN, declare BOOLEAN here
    status           TINYINT,
    created_at       TIMESTAMP(3),   -- DATETIME(3), UTC+8 wall-clock time
    row_kind         STRING METADATA FROM 'row_kind' VIRTUAL,   -- +I / -U / +U / -D of the binlog event
    -- balance_before, ext_txn_id and remark are not part of the event and are not read.
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector' = 'mysql-cdc',
    'hostname' = '__WALLET_DB_HOST__',
    'port' = '3306',
    'username' = 'bingo_cdc',
    'password' = '__CDC_PASSWORD__',
    'database-name' = 'bingo_wallet_[0-9]{2}',   -- regex: every wallet database on this instance
    'table-name' = 'wallet_txn',      -- regex; if wallet_txn is sharded into physical tables use e.g. 'wallet_txn_[0-9]+'
    -- One unique id per source reader, range larger than the parallelism, unique among all binlog clients
    -- of this TaurusDB instance.
    'server-id' = '5401-5408',
    'server-time-zone' = 'Asia/Manila',   -- must match the TaurusDB time_zone (+08:00)
    -- First start: snapshot the existing rows, then follow the binlog. Later restarts resume from the
    -- checkpoint/savepoint, not from this setting.
    'scan.startup.mode' = 'initial',
    'scan.incremental.snapshot.enabled' = 'true',
    'scan.read-changelog-as-append-only.enabled' = 'true',
    'heartbeat.interval' = '30s'
);

CREATE TABLE wallet_txn_kafka (
    `msg_key`        STRING,          -- Kafka record key only (not in the JSON value)
    `id`             BIGINT,
    `userId`         BIGINT,
    `userLine`       INT,
    `currency`       STRING,
    `txnType`        STRING,
    `direction`      INT,
    `amount`         DECIMAL(20, 4),
    `balanceAfter`   DECIMAL(20, 4),
    `providerCode`   STRING,
    `providerTxnId`  STRING,
    `roundId`        STRING,
    `gameCode`       STRING,
    `refTxnId`       STRING,
    `roundClosed`    BOOLEAN,
    `status`         INT,
    `createdAt`      STRING
) WITH (
    'connector' = 'kafka',
    'topic' = 'bingo.wallet.txn',
    'properties.bootstrap.servers' = '__KAFKA_SERVERS__',   -- DMS for Kafka private addresses
    'key.format' = 'raw',
    'key.fields' = 'msg_key',
    'value.format' = 'json',
    'value.fields-include' = 'EXCEPT_KEY',
    'value.json.encode.decimal-as-plain-number' = 'true',  -- 12.5000, never 1.25E+1
    'sink.partitioner' = 'default',                        -- Kafka partitioner: murmur2(key)
    -- At-least-once + consumer de-duplication by id. For exactly-once use 'exactly-once' together with
    -- 'sink.transactional-id-prefix' and read_committed consumers.
    'sink.delivery-guarantee' = 'at-least-once',
    'properties.acks' = 'all',
    'properties.enable.idempotence' = 'true',
    'properties.compression.type' = 'lz4'
);

INSERT INTO wallet_txn_kafka
SELECT
    CAST(user_id AS STRING)                                    AS `msg_key`,
    id                                                         AS `id`,
    user_id                                                    AS `userId`,
    user_line                                                  AS `userLine`,
    currency                                                   AS `currency`,
    txn_type                                                   AS `txnType`,
    CAST(direction AS INT)                                     AS `direction`,
    amount                                                     AS `amount`,
    balance_after                                              AS `balanceAfter`,
    provider_code                                              AS `providerCode`,
    provider_txn_id                                            AS `providerTxnId`,
    round_id                                                   AS `roundId`,
    game_code                                                  AS `gameCode`,
    ref_txn_id                                                 AS `refTxnId`,
    COALESCE(round_closed, CAST(0 AS TINYINT)) <> 0            AS `roundClosed`,
    CAST(status AS INT)                                        AS `status`,
    -- created_at is UTC+8 wall-clock time -> 2026-09-30T16:00:00.123+08:00
    DATE_FORMAT(created_at, 'yyyy-MM-dd''T''HH:mm:ss.SSS''+08:00''') AS `createdAt`
FROM wallet_txn_src
WHERE row_kind = '+I';
