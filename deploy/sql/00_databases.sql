-- One logical database per service (local development and the shared TaurusDB instance).
-- With SPRING_PROFILES_ACTIVE=sharding (test environment, production) the wallet, bet-record and turnover use 16
-- databases each instead, bingo_wallet_00..15 etc., created with shard_schemas.sh.
CREATE DATABASE IF NOT EXISTS bingo_user        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_wallet      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_game        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_lobby       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_bet_record  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_payment     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_risk        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_promotion   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_reconcile   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_turnover    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bingo_kyc         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS xxl_job           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Local development account. Production: one account per service with grants on its own schema only.
CREATE USER IF NOT EXISTS 'bingo'@'%' IDENTIFIED BY 'bingo';
GRANT ALL PRIVILEGES ON bingo_user.*       TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_wallet.*     TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_game.*       TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_lobby.*      TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_bet_record.* TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_payment.*    TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_risk.*       TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_promotion.*  TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_reconcile.*  TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_turnover.*   TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON bingo_kyc.*        TO 'bingo'@'%';
GRANT ALL PRIVILEGES ON xxl_job.*          TO 'bingo'@'%';
FLUSH PRIVILEGES;
