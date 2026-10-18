#!/usr/bin/env bash
# Isolated API verification. Requires scripts/build.sh output; only loopback fixtures.
set -euo pipefail
VDIR="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$VDIR/../.." && pwd)"
source "$VDIR/common.sh"
VER="$(awk '/<artifactId>sud-report-panel<\/artifactId>/{getline; gsub(/.*<version>|<\/version>.*/,""); print; exit}' "$PROJ/pom.xml")"
JAR="${SP_JAR:-$PROJ/target/sud-report-panel-${VER}.jar}"
[ -f "$JAR" ] || { echo 'Build the project first with bash scripts/build.sh (or set SP_JAR).' >&2; exit 1; }
check_ports 18797 18888
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sudpanel-api-verify.XXXXXX")"
cp "$JAR" "$RUN_DIR/app.jar"
mkdir -p "$RUN_DIR/data" "$RUN_DIR/config"
printf '# Empty isolated fixture\n' > "$RUN_DIR/config/resource-ids.properties"
export SUDPANEL_DATA_DIR="$RUN_DIR"
export SP_API_BASE=http://127.0.0.1:18797/api/apitest
export SP_API_LC_BASE=http://127.0.0.1:18797/api/logcat
export SP_API_MOCK=http://127.0.0.1:18888
export SP_DB="$RUN_DIR/data/sudpanel.db"
APPPID=''; MOCKPID=''
cleanup() {
  for pid in "$APPPID" "$MOCKPID"; do
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  done
  echo "Isolated verification logs and data: $RUN_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
start_app() {
  "$JAVA_BIN" -jar "$RUN_DIR/app.jar" --server.address=127.0.0.1 --server.port=18797 \
    --sudpanel.db-path="$SP_DB" --sudpanel.adb-path=disabled \
    --sudpanel.resource-id-file="$RUN_DIR/config/resource-ids.properties" > "$RUN_DIR/app-$1.log" 2>&1 &
  APPPID=$!
  wait_http http://127.0.0.1:18797/api/meta "$APPPID"
}
"$PY" "$VDIR/mock.py" > "$RUN_DIR/mock.log" 2>&1 &
MOCKPID=$!
wait_http http://127.0.0.1:18888/ok "$MOCKPID"
start_app first
"$PY" - <<'PY'
import json, urllib.request
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
for name in ['批量加入','游戏开始','游戏结束','房间清理']:
    data=dict(name=name,method='POST',url='http://127.0.0.1:18888/ok',note='Synthetic local fixture',expectStatus='200',expectCode='',timeoutMs=10000,insecure=False,enabled=True)
    with http.open(urllib.request.Request('http://127.0.0.1:18797/api/apitest/cases',data=json.dumps(data).encode(),headers={'Content-Type':'application/json'}),timeout=10) as response:
        assert response.status < 300
PY
RC=0
check() { if "$@"; then return 0; else RC=1; return 0; fi; }
for test in json-text var-suggest logcat sticky-scroll scroll-anim import-variable-mapping; do
  check "$NODE" "$VDIR/$test-check.mjs"
done
check "$PY" "$VDIR/verify.py"
check "$PY" "$VDIR/env-api-check.py"
snapshot_codes() {
  "$PY" - "$1" <<'PY'
import json,sys,urllib.request
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
with http.open('http://127.0.0.1:18797/api/apitest/codes',timeout=10) as response:
    values=json.load(response)
with open(sys.argv[1],'w') as output:
    json.dump(sorted(values,key=lambda item: json.dumps(item,sort_keys=True)),output,sort_keys=True)
PY
}
snapshot_codes "$RUN_DIR/codes-before.json"
kill "$APPPID"; wait "$APPPID" 2>/dev/null || true; APPPID=''
start_app restart
snapshot_codes "$RUN_DIR/codes-after.json"
check "$PY" - "$RUN_DIR" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1])
assert json.loads((root/'codes-before.json').read_text())==json.loads((root/'codes-after.json').read_text()), 'Return-code dictionary changed after restart'
print('PASS restart preserves the return-code dictionary')
PY
exit "$RC"
