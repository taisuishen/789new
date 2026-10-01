-- Test stand-ins (MySQL) for the StarRocks tables of deploy/starrocks/bingo_dw.sql that ReconcileDw reads.
-- Same column names and meaning; the primary keys play the role of StarRocks' Primary Key tables.
USE bingo_reconcile;

CREATE TABLE wallet_txn (
    id              BIGINT         NOT NULL PRIMARY KEY,
    created_at      DATETIME       NOT NULL,
    user_id         BIGINT         NOT NULL,
    user_line       INT            NOT NULL,
    currency        VARCHAR(8)     NOT NULL,
    txn_type        VARCHAR(24)    NOT NULL,
    direction       TINYINT        NOT NULL,
    amount          DECIMAL(20, 4) NOT NULL,
    provider_code   VARCHAR(32)    NOT NULL,
    round_id        VARCHAR(128)   NULL,
    game_code       VARCHAR(64)    NULL,
    status          TINYINT        NOT NULL
);

CREATE TABLE game_round (
    provider_code VARCHAR(32)    NOT NULL,
    round_id      VARCHAR(128)   NOT NULL,
    user_id       BIGINT         NOT NULL,
    currency      VARCHAR(8)     NOT NULL,
    bet_amount    DECIMAL(20, 4) NOT NULL,
    payout_amount DECIMAL(20, 4) NOT NULL,
    status        VARCHAR(16)    NOT NULL,
    bet_at        DATETIME       NULL,
    settled_at    DATETIME       NOT NULL,
    revision      INT            NOT NULL,
    PRIMARY KEY (provider_code, round_id, user_id)
);

CREATE TABLE provider_bet (
    provider_code   VARCHAR(32)    NOT NULL,
    provider_bet_id VARCHAR(128)   NOT NULL,
    round_id        VARCHAR(128)   NULL,
    user_id         BIGINT         NOT NULL,
    user_line       INT            NOT NULL,
    currency        VARCHAR(8)     NOT NULL,
    bet_amount      DECIMAL(20, 4) NOT NULL,
    payout_amount   DECIMAL(20, 4) NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    bet_at          DATETIME       NOT NULL,
    PRIMARY KEY (provider_code, provider_bet_id)
);
