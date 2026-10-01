USE bingo_game;

-- Transfer-wallet orders. orderNo is the idempotency key for both the provider call and the wallet call.
CREATE TABLE IF NOT EXISTS transfer_order (
    id            BIGINT        NOT NULL,
    order_no      VARCHAR(40)   NOT NULL,
    user_id       BIGINT        NOT NULL,
    user_line     INT           NOT NULL DEFAULT 1 COMMENT 'player''s line when the order was created',
    provider_code VARCHAR(32)   NOT NULL,
    currency      VARCHAR(8)    NOT NULL,
    direction     VARCHAR(8)    NOT NULL COMMENT 'IN = platform -> provider, OUT = provider -> platform',
    amount        DECIMAL(20,4) NOT NULL,
    status        VARCHAR(20)   NOT NULL COMMENT 'INIT, WALLET_DEBITED, PROVIDER_DONE, UNKNOWN, SUCCEEDED, FAILED, REFUNDED',
    provider_ref  VARCHAR(128)  NULL,
    attempts      INT           NOT NULL DEFAULT 0,
    last_error    VARCHAR(512)  NULL,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_status_updated (status, updated_at),
    KEY idx_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'transfer-wallet orders';

-- Token-bound stakes of providers that query their open rounds (YGR betSlip/roundCheck, see OpenBetLedger), with the
-- game token they were placed with: PENDING is written before the wallet call, OPEN once the wallet debited it,
-- CLOSED when it was paid out, refunded or refused. PENDING and OPEN rows are what the provider gets back; the token
-- identifies the player for the payout / refund of the stake after it expired. CLOSED rows are deleted by
-- openBetRetentionJob.
CREATE TABLE IF NOT EXISTS open_bet (
    id            BIGINT        NOT NULL,
    provider_code VARCHAR(32)   NOT NULL,
    txn_id        VARCHAR(128)  NOT NULL COMMENT 'the stake''s provider txn id, as in wallet_txn.provider_txn_id',
    round_id      VARCHAR(128)  NOT NULL,
    user_id       BIGINT        NOT NULL,
    currency      VARCHAR(8)    NOT NULL,
    game_code     VARCHAR(64)   NULL,
    session_token VARCHAR(128)  NOT NULL,
    amount        DECIMAL(20,4) NULL COMMENT 'null while a take-all stake is PENDING',
    status        VARCHAR(8)    NOT NULL COMMENT 'PENDING, OPEN, CLOSED',
    placed_at     DATETIME(3)   NOT NULL COMMENT 'UTC+8, when the stake arrived',
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_txn (provider_code, txn_id),
    KEY idx_open (provider_code, status, placed_at),
    KEY idx_round (provider_code, round_id),
    KEY idx_token (session_token),
    KEY idx_status_updated (status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'open token-bound stakes of providers that query them';
