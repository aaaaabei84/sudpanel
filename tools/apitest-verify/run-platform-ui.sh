#!/usr/bin/env bash
# Browser fixtures against generated static files. No Java, SQLite, or devices.
set -euo pipefail
VDIR="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$VDIR/../.." && pwd)"
source "$VDIR/common.sh"
STATIC="${SP_STATIC_DIR:-$PROJ/src/main/resources/static}"
CHROME="$(resolve_chrome)"
PORT="${SP_PLATFORM_PORT:-18883}"; CDP="${SP_PLATFORM_CDP_PORT:-9335}"
check_ports "$PORT" "$CDP"
[ -f "$STATIC/index.html" ] || { echo 'Run bash scripts/build.sh first.' >&2; exit 1; }
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sudpanel-platform-ui.XXXXXX")"
SERVER=''; CHR=''
cleanup() {
  for pid in "$SERVER" "$CHR"; do
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  done
  echo "Isolated UI artifacts: $RUN_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
"$PY" -m http.server "$PORT" --bind 127.0.0.1 --directory "$STATIC" > "$RUN_DIR/static.log" 2>&1 &
SERVER=$!
wait_http "http://127.0.0.1:$PORT/" "$SERVER"
"$CHROME" --headless=new --disable-background-networking --no-first-run --no-default-browser-check --no-proxy-server \
  '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost' \
  --remote-debugging-address=127.0.0.1 --remote-debugging-port="$CDP" --user-data-dir="$RUN_DIR/profile" \
  --window-size=1440,1000 about:blank > "$RUN_DIR/chrome.log" 2>&1 &
CHR=$!
wait_http "http://127.0.0.1:$CDP/json/version" "$CHR"
export SP_APP_URL="http://127.0.0.1:$PORT/" SP_CDP="http://127.0.0.1:$CDP" SP_OUTPUT_DIR="$RUN_DIR"
"$NODE" "${SP_PLATFORM_UI_CHECK_SCRIPT:-$VDIR/platform-ui-check.mjs}"
