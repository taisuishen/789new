#!/bin/sh
# Prints the DDL of the sharded databases (test environment and production, SPRING_PROFILES_ACTIVE=sharding): one
# database per datasource, bingo_<kind>_NN, each with the full table set of that kind's script.
#
#   sh deploy/sql/shard_schemas.sh wallet > wallet_shards.sql              # 00..15, e.g. all on one instance (test)
#   sh deploy/sql/shard_schemas.sh wallet 08 15 | mysql -h <instance B> …  # only the databases living on B
#
# Run the output with the mysql client or in DAS (SQL script execution). Kinds and their scripts:
#   wallet -> 02_wallet.sql, bet_record -> 05_bet_record.sql, turnover -> 10_turnover.sql
# Local development (docker-compose, no sharding profile) keeps the single databases bingo_wallet / bingo_bet_record /
# bingo_turnover from 00_databases.sql instead.
# Grant each service account all databases of its kind with a wildcard ("_" must be escaped), e.g.
#   GRANT ALL PRIVILEGES ON `bingo\_wallet\_%`.* TO 'bingo_wallet'@'%';
set -eu

kind=${1:?usage: shard_schemas.sh wallet|bet_record|turnover [from] [to]}
# "08" -> 8 (a leading zero would make shell arithmetic read it as octal)
number() { n=$(printf '%s' "$1" | sed 's/^0*//'); echo "${n:-0}"; }
from=$(number "${2:-00}")
to=$(number "${3:-15}")
case "$kind" in
  wallet)     script=02_wallet.sql ;;
  bet_record) script=05_bet_record.sql ;;
  turnover)   script=10_turnover.sql ;;
  *) echo "unknown kind: $kind (wallet, bet_record or turnover)" >&2; exit 1 ;;
esac
ddl="$(dirname "$0")/$script"
if [ "$(head -n 1 "$ddl" | tr -d '\r')" != "USE bingo_$kind;" ]; then
  echo "$ddl must start with: USE bingo_$kind;" >&2
  exit 1
fi

i=$from
while [ "$i" -le "$to" ]; do
  db=$(printf 'bingo_%s_%02d' "$kind" "$i")
  echo "CREATE DATABASE IF NOT EXISTS $db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
  echo "USE $db;"
  tail -n +2 "$ddl"
  echo
  i=$((i + 1))
done
