#!/usr/bin/env bash
# Runs the bingo-* services on this machine from their packaged jars (mvn -DskipTests package), with the
# environment of deploy/local.env. Logs: deploy/local/logs/<service>.log.
#
#   deploy/local/run.sh start [service...]   # default: the login / register / KYC set (user wallet kyc gateway)
#   deploy/local/run.sh start all
#   deploy/local/run.sh stop [service...]    # default: every running one
#   deploy/local/run.sh status
#   deploy/local/run.sh logs <service>
#
# Service names: gateway user wallet game-integration lobby bet-record payment risk promotion reconcile turnover kyc.
# The gateway listens on GATEWAY_PORT (default 8090: 8080 is often taken locally).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/deploy/local"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/local.env}"
LOGS="$HERE/logs"
GATEWAY_PORT="${GATEWAY_PORT:-8090}"
ALL=(user wallet game-integration lobby bet-record payment risk promotion reconcile turnover kyc gateway)
DEFAULT=(user wallet kyc gateway)

# service -> worker id (unique per instance) and XXL-Job executor port (bash 3.2: no associative arrays)
worker_of() {
  case $1 in
    user) echo 1;; wallet) echo 2;; game-integration) echo 3;; lobby) echo 4;; bet-record) echo 5;; payment) echo 6;;
    risk) echo 7;; promotion) echo 8;; reconcile) echo 9;; gateway) echo 10;; turnover) echo 11;; kyc) echo 12;;
  esac
}
xxl_port_of() {
  case $1 in
    user) echo 9101;; wallet) echo 9102;; game-integration) echo 9103;; lobby) echo 9104;; bet-record) echo 9105;;
    payment) echo 9106;; risk) echo 9107;; promotion) echo 9108;; reconcile) echo 9109;; turnover) echo 9110;; kyc) echo 9111;;
  esac
}

jar_of() {
  if [[ $1 == gateway ]]; then echo "$ROOT/bingo-gateway/target/bingo-gateway-0.1.0-SNAPSHOT.jar"
  else echo "$ROOT/bingo-$1/bingo-$1-service/target/bingo-$1-service-0.1.0-SNAPSHOT.jar"; fi
}

pid_of() {
  local f="$LOGS/$1.pid"
  [[ -f $f ]] && kill -0 "$(cat "$f")" 2>/dev/null && cat "$f"
}

start_one() {
  local svc=$1 jar
  jar=$(jar_of "$svc")
  local worker xxl
  worker=$(worker_of "$svc"); xxl=$(xxl_port_of "$svc")
  [[ -n $worker ]] || { echo "unknown service: $svc" >&2; return 1; }
  [[ -f $jar ]] || { echo "$svc: $jar missing, run: mvn -DskipTests package" >&2; return 1; }
  if pid=$(pid_of "$svc"); then echo "$svc already running (pid $pid)"; return 0; fi
  (
    set -a; source "$ENV_FILE"; set +a
    export WORKER_ID="$worker"
    [[ $svc == gateway ]] && export SERVER_PORT="$GATEWAY_PORT"
    [[ -n $xxl ]] && export XXL_JOB_EXECUTOR_ADDRESS="http://host.docker.internal:$xxl"
    nohup java -XX:+UseG1GC -Xms256m -Xmx768m -jar "$jar" >"$LOGS/$svc.log" 2>&1 &
    echo $! >"$LOGS/$svc.pid"
  )
  echo "$svc started (pid $(cat "$LOGS/$svc.pid"), log deploy/local/logs/$svc.log)"
}

stop_one() {
  local svc=$1 pid
  if pid=$(pid_of "$svc"); then
    kill "$pid"
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
    echo "$svc stopped"
  fi
  rm -f "$LOGS/$svc.pid"
}

mkdir -p "$LOGS"
[[ -f $ENV_FILE ]] || { echo "missing $ENV_FILE (see deploy/README.md section 5)" >&2; exit 1; }
cmd=${1:-status}; shift || true
case $cmd in
  start)
    if [[ ${1:-} == all ]]; then set -- "${ALL[@]}"; elif [[ $# -eq 0 ]]; then set -- "${DEFAULT[@]}"; fi
    for s in "$@"; do start_one "$s"; done ;;
  stop)
    [[ $# -eq 0 ]] && set -- "${ALL[@]}"
    for s in "$@"; do stop_one "$s"; done ;;
  status)
    for s in "${ALL[@]}"; do
      if pid=$(pid_of "$s"); then echo "$s running (pid $pid)"; else echo "$s -"; fi
    done ;;
  logs)
    tail -n 200 -f "$LOGS/${1:?service}.log" ;;
  *)
    sed -n '2,12p' "$0"; exit 1 ;;
esac
