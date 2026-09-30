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
