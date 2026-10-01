-- LOCAL DEVELOPMENT ONLY (docker-compose runs every script of this folder on an empty volume).
-- NEVER run this on TaurusDB: a well-known password with access to every schema. Test and production use one
-- account per service, granted on its own schema only (docs/go-live.md).
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
