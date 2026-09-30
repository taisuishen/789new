USE bingo_wallet;

-- Balance per player and currency. Clustered on (user_id, currency): every money operation is a single
-- conditional UPDATE on this key. Platform/agent aggregate accounts are NOT kept here (they would become a
-- global hot row); reconcile computes them asynchronously.
CREATE TABLE IF NOT EXISTS wallet (
    id          BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    currency    VARCHAR(8)    NOT NULL,
    shard_no    SMALLINT      NOT NULL DEFAULT 0 COMMENT 'logical shard (0..1023) of user_id; used to select users when a shard moves',
    user_line   INT           NOT NULL DEFAULT 1 COMMENT 'copy of the player''s line (wallet_user_line), stamped on every ledger row',
    balance     DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT 'available; negative only through provider reversals when policy = ALLOW',
    frozen      DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT 'withdrawals waiting for audit / payout',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1 ACTIVE, 2 BET_LOCKED (self-exclusion, KYC), 3 FROZEN (AML hold)',
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, currency),
    UNIQUE KEY uk_id (id),
    KEY idx_shard (shard_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'player wallets';

-- Append-only ledger (never UPDATEd; only the retention job deletes rows older than the idempotency window).
-- Written in the same local transaction as the balance change.
-- Not partitioned on purpose: MySQL requires the partition column in every unique key, and the idempotency
-- key must stay global (so no daily partitions / DROP PARTITION here). History does not depend on this table: the
-- CDC job emits INSERTs only (row_kind = '+I') and StarRocks loads bingo.wallet.txn from Kafka (Routine Load);
-- player history within the window is read from the TaurusDB read-only nodes. This table keeps the idempotency window only (e.g. 7 days); a retention
-- job (TODO) deletes older rows oldest-id-first in small, throttled, off-peak batches.
-- This table is also the event source: binlog -> Flink CDC -> Kafka bingo.wallet.txn (deploy/flink).
CREATE TABLE IF NOT EXISTS wallet_txn (
    id              BIGINT        NOT NULL COMMENT 'snowflake, generated while holding the wallet row lock',
    user_id         BIGINT        NOT NULL,
    user_line       INT           NOT NULL DEFAULT 1 COMMENT 'player''s line when the row was written (snapshot, never updated)',
    currency        VARCHAR(8)    NOT NULL,
    txn_type        VARCHAR(24)   NOT NULL COMMENT 'BET, PAYOUT, FREE_PAYOUT, JACKPOT_PAYOUT, PROMO_PAYOUT, ROLLBACK, PAYOUT_REVERSAL, ADJUST, DEPOSIT, WITHDRAW_*, BONUS, REBATE, TRANSFER_*',
    direction       TINYINT       NOT NULL COMMENT '-1 debit, 1 credit, 0 no balance change',
    amount          DECIMAL(20,4) NOT NULL COMMENT 'always >= 0, sign is in direction',
    balance_before  DECIMAL(20,4) NOT NULL,
    balance_after   DECIMAL(20,4) NOT NULL,
    provider_code   VARCHAR(32)   NOT NULL COMMENT 'game provider, or PAYMENT / PROMOTION / TRANSFER:{provider} for platform transactions',
    provider_txn_id VARCHAR(128)  NOT NULL COMMENT 'provider transaction id or business number; for ROLLBACK / PAYOUT_REVERSAL the target''s id',
    ext_txn_id      VARCHAR(128)  NULL COMMENT 'provider id of the rollback request itself',
    round_id        VARCHAR(128)  NULL,
    game_code       VARCHAR(64)   NULL,
    ref_txn_id      VARCHAR(128)  NULL COMMENT 'provider txn id of the settled bet / reversed / adjusted transaction',
    round_closed    TINYINT       NOT NULL DEFAULT 0,
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '1 normal, 3 tombstone (bet cancelled before it arrived)',
    remark          VARCHAR(255)  NULL,
    created_at      DATETIME(3)   NOT NULL COMMENT 'UTC+8',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency (provider_code, provider_txn_id, txn_type),
    KEY idx_user_created (user_id, created_at),
    KEY idx_round (provider_code, round_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'wallet ledger';

-- Locks keyed by reason; wallet.status is kept equal to the strictest lock (see WalletTxnExecutor.updateStatus).
CREATE TABLE IF NOT EXISTS wallet_lock (
    user_id     BIGINT      NOT NULL,
    currency    VARCHAR(8)  NOT NULL COMMENT '* = every currency of the user, including wallets opened later',
    reason      VARCHAR(64) NOT NULL COMMENT 'SELF_EXCLUSION, COOL_OFF, KYC, AML_HOLD ...',
    status      TINYINT     NOT NULL COMMENT '2 BET_LOCKED, 3 FROZEN',
    created_at  DATETIME(3) NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, currency, reason)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'wallet locks by reason';

CREATE TABLE IF NOT EXISTS wallet_status_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    user_line   INT          NOT NULL DEFAULT 1,
    currency    VARCHAR(8)   NULL COMMENT 'NULL = all currencies',
    status      TINYINT      NOT NULL,
    reason      VARCHAR(255) NOT NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'audit trail of wallet status changes';

-- The player's line as last sent by user-service (the owner of user lines), versioned so retries and reordered
-- updates never apply an older line. wallet.user_line is a copy per currency row, read in the same statement as the
-- balance, so the hot path needs no extra query; wallets opened later (new currency) take the line from here.
-- A missing row means the default line 1.
CREATE TABLE IF NOT EXISTS wallet_user_line (
    user_id    BIGINT      NOT NULL,
    user_line  INT         NOT NULL,
    version    BIGINT      NOT NULL COMMENT 'user_account.line_version',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'player line owned by user-service';
