#!/usr/bin/env bash
# Shared prerequisites for isolated, loopback-only verification.
resolve_tool() {
  local value="$1" fallback="$2" result
  result="$(command -v "${value:-$fallback}" 2>/dev/null || true)"
  [ -n "$result" ] && [ -x "$result" ] || { echo "Missing executable: ${value:-$fallback}" >&2; return 1; }
  printf '%s\n' "$result"
}
PY="$(resolve_tool "${PY:-}" python3)" || exit 1
NODE="$(resolve_tool "${NODE:-}" node)" || exit 1
JAVA_BIN="$(resolve_tool "${JAVA_BIN:-${JAVA_HOME:+$JAVA_HOME/bin/java}}" java)" || exit 1
export PY NODE JAVA_BIN PYTHONDONTWRITEBYTECODE=1
export SP_ADB_DISABLED=1
# All test-owned Java services use the application-level ADB disabled sentinel.

check_ports() {
  "$PY" - "$@" <<'PY'
import socket, sys
ports = [int(value) for value in sys.argv[1:]]
if len(set(ports)) != len(ports):
    raise SystemExit('Isolated test ports must be distinct.')
for port in ports:
    if not 1024 <= port <= 65535 or port in (18790, 18890):
        raise SystemExit(f'Refusing the application port or invalid test port: {port}')
    try:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    except OSError as error:
        raise SystemExit(f'Port {port} is occupied; existing services will not be reused: {error}')
PY
}

resolve_chrome() {
  local candidate
  if [ -n "${CHROME:-}" ]; then resolve_tool "$CHROME" chromium; return; fi
  for candidate in chromium chromium-browser google-chrome google-chrome-stable; do
    if command -v "$candidate" >/dev/null 2>&1; then resolve_tool "$candidate" "$candidate"; return; fi
  done
  candidate='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  [ -x "$candidate" ] && { printf '%s\n' "$candidate"; return; }
  echo 'Chrome/Chromium is required for UI verification. Set CHROME.' >&2
  return 1
}

wait_http() {
  local url="$1" process="$2"
  "$PY" - "$url" "$process" <<'PY'
import os, sys, time, urllib.parse, urllib.request
url, pid = sys.argv[1], int(sys.argv[2])
parsed = urllib.parse.urlsplit(url)
if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1':
    raise SystemExit('Only a loopback HTTP endpoint is allowed.')
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
deadline = time.monotonic() + 40
while time.monotonic() < deadline:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        raise SystemExit('The test-owned process exited before it was ready.')
    try:
        with opener.open(url, timeout=1) as response:
            if response.status == 200:
                raise SystemExit(0)
    except (OSError, ValueError):
        pass
    time.sleep(.1)
raise SystemExit('The isolated service did not become ready: ' + url)
PY
}
