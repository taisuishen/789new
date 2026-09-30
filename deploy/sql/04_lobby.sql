USE bingo_lobby;

-- Providers are onboarded by ops (contract + integration go-live), never created by automation.
-- status: ACTIVE | MAINTENANCE (operator) | AUTO_MAINTENANCE (game-integration circuit breaker) | DISABLED (operator).
-- Automation only moves ACTIVE <-> AUTO_MAINTENANCE, with conditional updates.
CREATE TABLE IF NOT EXISTS game_provider (
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    wallet_mode   VARCHAR(16)  NOT NULL COMMENT 'SEAMLESS | TRANSFER',
    status        VARCHAR(20)  NOT NULL DEFAULT 'MAINTENANCE',
    status_reason VARCHAR(255) NULL,
    sort          INT          NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'game providers';

-- Provider-owned columns (name, category, theoretical_rtp, thumbnail, device flags) are refreshed by
-- lobbyGameSyncJob; status, sort and game_type are operator-owned. New games arrive OFFLINE for certification review.
-- game_type (GameType: SLOT, FISHING, POKER, LIVE, TABLE, ARCADE, BINGO, LOTTERY, SPORTS, ESPORTS, OTHER) is copied
-- onto every bet record and drives game-type wagering requirements and bonuses; it is guessed from the provider's
-- category on insert and corrected by ops during the certification review.
CREATE TABLE IF NOT EXISTS game (
    id                BIGINT        NOT NULL COMMENT 'snowflake',
    provider_code     VARCHAR(32)   NOT NULL,
    game_code         VARCHAR(64)   NOT NULL,
    name              VARCHAR(255)  NOT NULL,
    category          VARCHAR(32)   NOT NULL DEFAULT '',
    game_type         VARCHAR(32)   NOT NULL DEFAULT 'OTHER' COMMENT 'GameType; operator-owned after insert',
    theoretical_rtp   DECIMAL(6, 3) NULL COMMENT 'percent, e.g. 96.500 (certified value)',
    thumbnail_url     VARCHAR(512)  NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE | OFFLINE',
    mobile_supported  TINYINT(1)    NOT NULL DEFAULT 1,
    desktop_supported TINYINT(1)    NOT NULL DEFAULT 1,
    sort              INT           NOT NULL DEFAULT 0 COMMENT 'ascending display order',
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_game (provider_code, game_code),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'game catalogue';

CREATE TABLE IF NOT EXISTS game_category (
    code VARCHAR(32)  NOT NULL,
    name VARCHAR(128) NOT NULL,
    sort INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'lobby categories';

INSERT INTO game_category (code, name, sort)
VALUES ('SLOT', 'Slots', 10),
       ('LIVE', 'Live Casino', 20),
       ('TABLE', 'Table Games', 30),
       ('FISHING', 'Fishing', 40),
       ('POKER', 'Poker & Cards', 45),
       ('BINGO', 'Bingo', 50),
       ('ARCADE', 'Arcade', 60) AS new
ON DUPLICATE KEY UPDATE name = new.name, sort = new.sort;
