#!/bin/bash
# Unique temporary SQLite + loopback fixture + explicitly disabled ADB.
# SP_JAR=/absolute/path/to/jar bash run-client.sh
set -uo pipefail

VDIR="$(cd "$(dirname "$0")" && pwd)"
PROJ="${SP_PROJECT:-$(cd "$VDIR/../.." && pwd)}"
source "$VDIR/common.sh"
JAR="${SP_JAR:-}"
if [ -z "$JAR" ]; then
  VER="$(awk '/<artifactId>sud-report-panel<\/artifactId>/{getline; gsub(/.*<version>|<\/version>.*/,""); print; exit}' "$PROJ/pom.xml")"
  JAR="$PROJ/target/sud-report-panel-${VER}.jar"
fi
if [ ! -f "$JAR" ]; then
  echo "找不到 jar，请先构建或设置 SP_JAR: $JAR" >&2
  exit 1
fi

"$PY" - <<'PY'
import os, socket
ports = [18795, 18889] + ([9223] if os.environ.get('SP_RUN_UI') == '1' else [])
for port in ports:
    try:
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('127.0.0.1', port))
    except OSError as error:
        raise SystemExit(f'隔离验证端口 {port} 不可用，拒绝接入已有服务: {error}')
PY
PORT_RC=$?
if [ "$PORT_RC" -ne 0 ]; then exit "$PORT_RC"; fi

RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sudpanel-client-verify.XXXXXX")"
printf '%s\n' "isolated-client-regression" > "$RUN_DIR/owner.marker"
# Keep this run bound to the exact artifact even if another agent rebuilds target/.
cp "$JAR" "$RUN_DIR/app.jar" || exit 1
printf '# Empty isolated fixture\n' > "$RUN_DIR/resource-ids.properties"
export SUDPANEL_DATA_DIR="$RUN_DIR"
APPPID=""
MOCKPID=""
CHROMEPID=""
RC=1

cleanup() {
  if [ -n "$APPPID" ]; then kill "$APPPID" 2>/dev/null || true; wait "$APPPID" 2>/dev/null || true; fi
  if [ -n "$MOCKPID" ]; then kill "$MOCKPID" 2>/dev/null || true; wait "$MOCKPID" 2>/dev/null || true; fi
  if [ -n "$CHROMEPID" ]; then kill "$CHROMEPID" 2>/dev/null || true; wait "$CHROMEPID" 2>/dev/null || true; fi
  if [ "$RC" -eq 0 ] && [ "${SP_KEEP:-0}" != "1" ]; then
    "$PY" - "$RUN_DIR" <<'PY'
import pathlib, shutil, sys
root = pathlib.Path(sys.argv[1]).resolve()
if not root.name.startswith('sudpanel-client-verify.') or (root/'owner.marker').read_text().strip() != 'isolated-client-regression':
    raise SystemExit('临时目录所有权校验失败，保留目录')
shutil.rmtree(root)
PY
  else
    echo "专项日志与 JSON summary 保留: $RUN_DIR"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

ready() {
  "$PY" - "$1" <<'PY'
import json, sys, time, urllib.request
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    try:
        with http.open(sys.argv[1], timeout=1) as response:
            if response.status == 200:
                json.loads(response.read())
                raise SystemExit(0)
    except (OSError, ValueError):
        pass
    time.sleep(.05)
raise SystemExit('服务就绪 deadline 超时: '+sys.argv[1])
PY
}

start_app() {
  local label="$1"
  "$JAVA_BIN" -jar "$RUN_DIR/app.jar" --server.address=127.0.0.1 --server.port=18795 \
    --sudpanel.db-path="$RUN_DIR/client.db" --sudpanel.adb-path=disabled \
    --sudpanel.resource-id-file="$RUN_DIR/resource-ids.properties" \
    > "$RUN_DIR/app-${label}.log" 2>&1 &
  APPPID=$!
  if ! ready http://127.0.0.1:18795/api/meta; then
    tail -60 "$RUN_DIR/app-${label}.log" >&2
    return 1
  fi
  kill -0 "$APPPID" 2>/dev/null || return 1
}

echo "隔离 HTTP 专项：$RUN_DIR"
"$PY" "$VDIR/client-mock.py" --port 18889 > "$RUN_DIR/mock.log" 2>&1 &
MOCKPID=$!
if ! ready http://127.0.0.1:18889/_control/ready; then exit 1; fi
if ! start_app first; then exit 1; fi

export SP_API_BASE=http://127.0.0.1:18795/api/apitest
export SP_API_MOCK=http://127.0.0.1:18889
"$PY" "$VDIR/client-verify.py" --phase exercise --manifest "$RUN_DIR/manifest.json" --summary "$RUN_DIR/exercise-summary.json"
RC=$?

if [ "${SP_RUN_UI:-0}" = "1" ]; then
  CHROME="$(resolve_chrome)" || exit 1
  if [ ! -x "$CHROME" ] || [ ! -x "$NODE" ]; then
    echo "FAIL Chrome/Node 不可用，不能跳过已要求的浏览器专项" >&2
    RC=1
  else
    "$CHROME" --headless=new --disable-background-networking --no-first-run --no-default-browser-check --no-proxy-server \
      '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost' --remote-debugging-address=127.0.0.1 \
      --user-data-dir="$RUN_DIR/chrome-profile" --remote-debugging-port=9223 \
      --window-size=1440,1000 about:blank > "$RUN_DIR/chrome.log" 2>&1 &
    CHROMEPID=$!
    if ready http://127.0.0.1:9223/json/version; then
      SP_APP_URL=http://127.0.0.1:18795/ SP_CDP=http://127.0.0.1:9223 \
      SP_UI_SUMMARY="$RUN_DIR/ui-summary.json" SP_UI_SCREENSHOT_DIR="$RUN_DIR" "$NODE" "$VDIR/client-ui-check.mjs"
      UI_RC=$?
      if [ "$UI_RC" -ne 0 ]; then RC=$UI_RC; fi
      SP_APP_URL=http://127.0.0.1:18795/ SP_CDP=http://127.0.0.1:9223 \
      SP_UI_SUMMARY="$RUN_DIR/night-ui-summary.json" "$NODE" "$VDIR/night-client-ui-check.mjs"
      NIGHT_UI_RC=$?
      if [ "$NIGHT_UI_RC" -ne 0 ]; then RC=$NIGHT_UI_RC; fi
    else
      RC=1
    fi
  fi
fi

# Real process restart twice against the same isolated database.
for ROUND in 1 2; do
  kill "$APPPID" 2>/dev/null || true
  wait "$APPPID" 2>/dev/null || true
  APPPID=""
  if ! start_app "restart-${ROUND}"; then RC=1; break; fi
  if [ -f "$RUN_DIR/manifest.json" ]; then
    "$PY" "$VDIR/client-verify.py" --phase restart --manifest "$RUN_DIR/manifest.json" --summary "$RUN_DIR/restart-${ROUND}-summary.json"
    PHASE_RC=$?
    if [ "$PHASE_RC" -ne 0 ]; then RC=$PHASE_RC; fi
  else
    echo "FAIL 重启夹具未保存，不能把重启验证记为通过" >&2
    RC=1
  fi
done

"$PY" - "$RUN_DIR" <<'PY'
import json, os, pathlib, sys
root = pathlib.Path(sys.argv[1])
results = [json.loads(p.read_text()) for p in sorted(root.glob('*-summary.json'))]
expected = ['exercise-summary.json', 'restart-1-summary.json', 'restart-2-summary.json']
if os.environ.get('SP_RUN_UI') == '1': expected.extend(['ui-summary.json', 'night-ui-summary.json'])
missing = [name for name in expected if not (root/name).is_file()]
summary = {'phases': len(results), 'total': sum(v['total'] for v in results),
           'passed': sum(v['passed'] for v in results), 'failed': sum(v['failed'] for v in results),
           'missingPhases': missing, 'complete': not missing,
           'adbDisabled': True, 'database': 'unique temporary SQLite', 'trueDeviceChecks': 0}
(root/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
print('HTTP 专项汇总: '+json.dumps(summary, ensure_ascii=False))
raise SystemExit(1 if missing else 0)
PY
AGG_RC=$?
if [ "$AGG_RC" -ne 0 ]; then RC=$AGG_RC; fi
echo "HTTP 专项退出码 = $RC"
exit "$RC"
