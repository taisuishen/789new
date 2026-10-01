#!/usr/bin/env bash
# LOCAL DEVELOPMENT ONLY: a public HTTPS address for RunPod (job webhook + KYC image downloads).
#
#   deploy/local/tunnel.sh start    # public_proxy.py + Cloudflare quick tunnel (brew install cloudflared)
#   deploy/local/tunnel.sh stop
#   deploy/local/tunnel.sh status
#
# The quick-tunnel address changes on every start; start rewrites everything that uses it:
#   <OBS_LOCAL_DIR>/.base-url              -> <url>/files       (LocalDiskStorage signs image URLs with it)
#   bingo_kyc.config RunPodWebhookUrl      -> <url>/callback/runpod/kyc (bingo-kyc re-reads config every 30 s)
# Only public_proxy.py is reachable through the tunnel; it forwards the webhook and signed image GETs, nothing else.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/deploy/local"
LOGS="$HERE/logs"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/local.env}"
PROXY_PORT="${PUBLIC_PROXY_PORT:-8199}"
mkdir -p "$LOGS"
set -a; source "$ENV_FILE"; set +a

running() { [[ -f $LOGS/$1.pid ]] && kill -0 "$(cat "$LOGS/$1.pid")" 2>/dev/null; }

stop() {
  for p in tunnel public-proxy; do
    if running "$p"; then kill "$(cat "$LOGS/$p.pid")"; echo "$p stopped"; fi
    rm -f "$LOGS/$p.pid"
  done
}

start() {
  command -v cloudflared >/dev/null || { echo "cloudflared missing: brew install cloudflared" >&2; exit 1; }
  stop >/dev/null
  mkdir -p "$OBS_LOCAL_DIR"
  nohup python3 "$HERE/public_proxy.py" >"$LOGS/public-proxy.log" 2>&1 &
  echo $! >"$LOGS/public-proxy.pid"
  nohup cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:$PROXY_PORT" >"$LOGS/tunnel.log" 2>&1 &
  echo $! >"$LOGS/tunnel.pid"

  local url=""
  for _ in $(seq 1 60); do
    url=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOGS/tunnel.log" | head -1 || true)
    [[ -n $url ]] && break
    sleep 1
  done
  [[ -n $url ]] || { echo "tunnel did not come up, see $LOGS/tunnel.log" >&2; exit 1; }
  echo "$url" >"$LOGS/public-url"
  echo "$url/files" >"$OBS_LOCAL_DIR/.base-url"

  if mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"$DB_USER" -p"$DB_PASSWORD" bingo_kyc \
       -e "UPDATE config SET cfg_value = '$url/callback/runpod/kyc' WHERE cfg_name = 'RunPodWebhookUrl'" 2>/dev/null; then
    echo "RunPodWebhookUrl updated"
  else
    echo "WARN: bingo_kyc.config not updated (schema not loaded yet?) - set RunPodWebhookUrl to $url/callback/runpod/kyc" >&2
  fi
  echo "public url: $url"
}

case ${1:-status} in
  start) start ;;
  stop) stop ;;
  status)
    for p in public-proxy tunnel; do running "$p" && echo "$p running" || echo "$p -"; done
    [[ -f $LOGS/public-url ]] && echo "public url: $(cat "$LOGS/public-url")" ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
