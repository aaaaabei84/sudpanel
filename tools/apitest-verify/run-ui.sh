#!/bin/bash
# 浏览器界面断言（ui-check.mjs）的一键脚本：起无头 Chrome → 起独立实例 → 跑断言 → 全部收掉。
#
# 为什么不直接跑 ui-check.mjs：它需要「一个跑着的实例 + 一个开着 CDP 端口 9222 的浏览器」两样东西，
# 而且这两样必须和断言在**同一次 shell 调用**里起完（沙箱会在一次调用结束时回收该调用内的后台子进程，
# 分开两次调用只会看到「连接被拒」—— 见 README 踩坑第 2 条）。
#
# 前置：先打包好前端再打包 jar，顺序不能反（见 README「跑界面断言之前必须先打包前端」）：
#   bash scripts/build.sh
#
# 全程只走 127.0.0.1 + 临时库（/tmp/sp-ui/），不碰正式实例与正式数据库。
#
# 用法（在项目根目录）：
#   bash tools/apitest-verify/run-ui.sh
set -uo pipefail

VDIR="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$VDIR/../.." && pwd)"
VER="$(awk '/<artifactId>sud-report-panel<\/artifactId>/{getline; gsub(/.*<version>|<\/version>.*/,""); print; exit}' "$PROJ/pom.xml")"
JAR="${SP_JAR:-$PROJ/target/sud-report-panel-${VER}.jar}"

source "$VDIR/common.sh"
CHROME="$(resolve_chrome)" || exit 1
PORT="${SP_PORT:-18799}"
check_ports "$PORT" 9222 18888 || exit 1
TMP="$(mktemp -d "${TMPDIR:-/tmp}/sudpanel-ui-verify.XXXXXX")"
export SUDPANEL_DATA_DIR="$TMP"

if [ ! -x "$CHROME" ]; then
  echo "找不到 Chrome：$CHROME" >&2
  echo "用 CHROME=/path/to/chrome 指定，或改用 CHROME=... 的 Chromium。" >&2
  exit 1
fi
if [ ! -f "$JAR" ]; then
  echo "找不到 jar：$JAR" >&2
  echo "请先执行：bash scripts/build.sh" >&2
  exit 1
fi

# 前端产物自 2026-10-10 起不入库（见 .gitignore）：从零克隆后必须先构建前端。
# 不拦这一道的话，jar 会被正常打出来、但里面没有前端 —— 界面断言整片红，
# 看起来像「功能坏了」，实际只是没人跑过 npm build。
if [ ! -f "$PROJ/src/main/resources/static/index.html" ]; then
  echo "找不到前端产物：src/main/resources/static/index.html" >&2
  echo "该目录已改为构建时生成、不入库，请先执行：" >&2
  echo "  bash scripts/build.sh" >&2
  exit 1
fi
# 这一步专门拦「前端源码改了但没重新 build」：界面断言验的是 jar 里那份产物，
# 产物比源码旧的话断言全红，看起来像功能坏了。产物比 src 新旧比较麻烦，这里只做时间提示。
NEWEST_TS="$(find "$PROJ/frontend/src" -type f -newer "$JAR" -print -quit 2>/dev/null || true)"
if [ -n "$NEWEST_TS" ]; then
  echo "⚠ 前端源码有比 jar 更新的文件（例如 ${NEWEST_TS#$PROJ/}）" >&2
  echo "  界面断言验的是 jar 里打包好的前端，先重新构建再跑，否则验的是旧界面：" >&2
  echo "  bash scripts/build.sh" >&2
  exit 1
fi

# 固定本轮制品，Spring Boot 嵌套 jar 仍可能在运行中懒读取。
cp "$JAR" "$TMP/app.jar" || exit 1
JAR="$TMP/app.jar"

cleanup() {
  echo "--- 收尾 ---"
  [ -n "${APP:-}" ] && kill "$APP" 2>/dev/null && echo "已停被测实例 $APP"
  [ -n "${CHR:-}" ] && kill "$CHR" 2>/dev/null && echo "已停 Chrome $CHR"
  [ -z "${APP:-}" ] || wait "$APP" 2>/dev/null || true
  [ -z "${CHR:-}" ] || wait "$CHR" 2>/dev/null || true
  echo "Isolated UI artifacts: $TMP"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

mkdir -p "$TMP/data" "$TMP/profile" "$TMP/config"
printf '# Empty isolated fixture\n' > "$TMP/config/resource-ids.properties"

echo "=== 1) 起无头 Chrome（CDP 9222）==="
"$CHROME" --headless=new --disable-background-networking --no-first-run --no-default-browser-check --no-proxy-server \
  '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost' --remote-debugging-address=127.0.0.1 \
  --user-data-dir="$TMP/profile" --remote-debugging-port=9222 \
  --window-size=1440,900 about:blank > "$TMP/chrome.log" 2>&1 &
CHR=$!
wait_http http://127.0.0.1:9222/json/version "$CHR" || exit 1
for i in $(seq 1 40); do
  curl -s --noproxy '*' -o /dev/null http://127.0.0.1:9222/json/version && break
  sleep 0.5
done
echo -n " CDP: "
curl -s --noproxy '*' http://127.0.0.1:9222/json/version | head -c 90
echo

echo
echo "=== 2) 起被测实例（端口 ${PORT}，临时空库）==="
"$JAVA_BIN" -jar "$JAR" \
  --server.address=127.0.0.1 --server.port="${PORT}" --sudpanel.db-path="$TMP/data/sudpanel.db" \
  --sudpanel.adb-path=disabled --sudpanel.resource-id-file="$TMP/config/resource-ids.properties" \
  > "$TMP/app.log" 2>&1 &
APP=$!
for i in $(seq 1 80); do
  kill -0 "$APP" 2>/dev/null || break
  curl -s --noproxy '*' -o /dev/null "http://127.0.0.1:${PORT}/api/meta" && break
  sleep 0.5
done
if ! kill -0 "$APP" 2>/dev/null || ! curl -sf --noproxy '*' -o /dev/null "http://127.0.0.1:${PORT}/api/meta"; then
  echo "被测实例未就绪，日志：$TMP/app.log" >&2
  exit 1
fi
echo -n " 版本: "
curl -s --noproxy '*' "http://127.0.0.1:${PORT}/api/meta"
echo

# 播一条用例。临时库是全新的、用例数为 0，而「用例管理」弹窗里最要紧的几条断言
# （卡片式两行、悬停放大、行内操作按钮）都挂在用例行上 —— 不播一条，它们只能被跳过，
# 而「跳过」在结果里看起来和「通过」一样绿。
# 地址用本机占位的假地址，不含任何真实域名与凭证。
curl -s --noproxy '*' -o /dev/null -X POST "http://127.0.0.1:${PORT}/api/apitest/cases" \
  -H 'Content-Type: application/json' \
  -d '{"name":"界面断言用占位用例","method":"POST","url":"http://127.0.0.1:18888/ok","headers":"{\"X-Env\":\"{{env}}\"}","body":"{\"roomId\":\"{{roomId}}\"}","note":"run-ui.sh 自动播的，只在临时库里","expectStatus":"200","expectCode":"0","timeoutMs":10000,"insecure":false,"enabled":true}'

# 再播一条**带响应体断言**的用例（2026-10-05，需求 #51）。
#
# 目标是本实例自己的 /api/meta（返回 {"name":"SudPanel","version":"…"}）：它一定在、返回的是 JSON，
# 而且不需要另起 mock —— 界面断言这一层只关心「断言怎么编辑、失败原因怎么显示」。
# 三条断言刻意都会失败，这样展开结果行时能看到**多行**原因，才验得到 whitespace-pre-wrap
# 有没有真的生效（单条失败只有一行，那种情况下「没生效」和「生效了」看起来一样）。
curl -s --noproxy '*' -o /dev/null -X POST "http://127.0.0.1:${PORT}/api/apitest/cases" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"界面断言用·响应体断言\",\"method\":\"GET\",\"url\":\"http://127.0.0.1:${PORT}/api/meta\",\"note\":\"run-ui.sh 自动播的，只在临时库里\",\"expectStatus\":\"200\",\"expectCode\":\"\",\"timeoutMs\":10000,\"insecure\":false,\"enabled\":true,\"asserts\":\"[{\\\"path\\\":\\\"version\\\",\\\"op\\\":\\\"equals\\\",\\\"expect\\\":\\\"9.9.9\\\"},{\\\"path\\\":\\\"name\\\",\\\"op\\\":\\\"equals\\\",\\\"expect\\\":\\\"Nope\\\"},{\\\"path\\\":\\\"version\\\",\\\"op\\\":\\\"type\\\",\\\"expect\\\":\\\"integer\\\"}]\"}"

# 再播一条**断言全部通过**的用例（2026-10-05，需求 #58）。
#
# 前两条分别落在「没配断言」和「全部不通过」两个状态上，而 #58 新增那一块最要紧的
# 恰恰是第三个状态：**配了断言、而且都过了** —— 没有这条用例，
# 「收起态显示『N 条全部通过』」这个断言就永远验不到（永远红，或者反过来被跳过）。
# 断言打的是本实例自己的 /api/meta（回 {"name":"SudPanel","version":"1.6.x"}），
# name 与 version 都是稳定字段，所以这两条必然通过。
curl -s --noproxy '*' -o /dev/null -X POST "http://127.0.0.1:${PORT}/api/apitest/cases" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"界面断言用·断言全过\",\"method\":\"GET\",\"url\":\"http://127.0.0.1:${PORT}/api/meta\",\"note\":\"run-ui.sh 自动播的，只在临时库里\",\"expectStatus\":\"200\",\"expectCode\":\"\",\"timeoutMs\":10000,\"insecure\":false,\"enabled\":true,\"asserts\":\"[{\\\"path\\\":\\\"name\\\",\\\"op\\\":\\\"equals\\\",\\\"expect\\\":\\\"SudPanel\\\"},{\\\"path\\\":\\\"version\\\",\\\"op\\\":\\\"type\\\",\\\"expect\\\":\\\"string\\\"}]\"}"

# 第四条：**一条断言都不配**、但请求能打通。
#
# 上面那条占位用例也「没断言」，可它打的是没启动的 mock，请求压根没通 ——
# 它的记录里根本没有判定结果这一项（null），界面上走的是「请求没通」那条文案。
# 「配了用例但没配断言」是**另一个状态**（空数组），得有一条能打通的用例才验得到，
# 否则这个分支要么永远红、要么被当成「验过了」。
curl -s --noproxy '*' -o /dev/null -X POST "http://127.0.0.1:${PORT}/api/apitest/cases" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"界面断言用·无断言\",\"method\":\"GET\",\"url\":\"http://127.0.0.1:${PORT}/api/meta\",\"note\":\"run-ui.sh 自动播的，只在临时库里\",\"expectStatus\":\"200\",\"expectCode\":\"\",\"timeoutMs\":10000,\"insecure\":false,\"enabled\":true}"

# 再插一条**占位游戏**（2026-10-06，需求 #68）。
#
# 性能页「推送身份（mg_id / app_id）」的断言全部挂在游戏行上，而临时库是空的 ——
# 扫描游戏要真机，本机没插设备时那一段会被整段跳过，跳过看起来和通过一样绿。
# 所以直接往临时库里插一条：验的是「页面能不能编辑并读回这两个值」，与扫描功能无关。
# 走 python 的 sqlite3（标准库，不引新依赖）；库是本次临时库，随 /tmp/sp-ui 一起回收。
"$PY" - "$TMP/data/sudpanel.db" <<'PYEOF'
import sqlite3, sys
con = sqlite3.connect(sys.argv[1], timeout=5)
con.execute("INSERT INTO perf_game(name,short_name,created_at) VALUES(?,?,?)",
            ("界面断言用占位游戏", "界面断言用占位游戏", "2026-10-06T00:00:00Z"))
con.commit()
con.close()
print(" 已插占位游戏 1 条（性能页的推送身份断言要用）")
PYEOF
echo " 已播 4 条用例（用例数 = $(curl -s --noproxy '*' "http://127.0.0.1:${PORT}/api/apitest/cases" | grep -o '"id"' | wc -l | tr -d ' ')，含 3 条断言必然失败 / 断言全过 / 无断言）"

echo
echo "=== 3) 跑浏览器界面断言 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/ui-check.mjs"
RC=$?

echo "=== 环境切换与保存回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/env-ui-check.mjs"
RC_ENV=$?
[ "$RC_ENV" -ne 0 ] && RC=$RC_ENV

echo "=== 性能与批次选择乱序回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/selection-race-ui-check.mjs"
RC_RACE=$?
[ "$RC_RACE" -ne 0 ] && RC=$RC_RACE

echo "=== 弹窗宽度与可见区域回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/dialog-layout-ui-check.mjs"
RC_LAYOUT=$?
[ "$RC_LAYOUT" -ne 0 ] && RC=$RC_LAYOUT

echo "=== 性能模式标定门槛回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/perf-calibration-ui-check.mjs"
RC_CALIBRATION=$?
[ "$RC_CALIBRATION" -ne 0 ] && RC=$RC_CALIBRATION

echo "=== 性能等待状态回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/perf-wait-ui-check.mjs"
RC_WAIT=$?
[ "$RC_WAIT" -ne 0 ] && RC=$RC_WAIT

echo "=== 性能进度恢复回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/perf-poll-ui-check.mjs"
RC_POLL=$?
[ "$RC_POLL" -ne 0 ] && RC=$RC_POLL

echo "=== 设备刷新失败与乱序回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/device-refresh-ui-check.mjs"
RC_DEVICE=$?
[ "$RC_DEVICE" -ne 0 ] && RC=$RC_DEVICE

echo "=== 上报监听跨页面保持回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/logcat-navigation-ui-check.mjs"
RC_LOGCAT_NAV=$?
[ "$RC_LOGCAT_NAV" -ne 0 ] && RC=$RC_LOGCAT_NAV

echo "=== 性能冻结计划与历史续作回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  "$NODE" "$VDIR/perf-continuation-ui-check.mjs"
RC_CONTINUATION=$?
[ "$RC_CONTINUATION" -ne 0 ] && RC=$RC_CONTINUATION

echo "=== 平台交互、上报级别与命中定位回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  SP_OUTPUT_DIR="$TMP/platform" "$NODE" "$VDIR/platform-ui-check.mjs"
RC_PLATFORM=$?
[ "$RC_PLATFORM" -ne 0 ] && RC=$RC_PLATFORM

echo
echo "=== JSON文件读取与导入交互回归 ==="
SP_APP_URL="http://127.0.0.1:${PORT}/" SP_CDP=http://127.0.0.1:9222 \
  SP_OUTPUT_DIR="$TMP/import-file" "$NODE" "$VDIR/import-file-ui-check.mjs"
RC_IMPORT_FILE=$?
[ "$RC_IMPORT_FILE" -ne 0 ] && RC=$RC_IMPORT_FILE

echo
echo "=== 性能结果运行长列表滚动保持回归（独立静态实例） ==="
SP_OUTPUT_DIR="$TMP/perf-analysis" \
  SP_PLATFORM_UI_CHECK_SCRIPT="$VDIR/perf-analysis-retention-ui-check.mjs" \
  bash "$VDIR/run-platform-ui.sh"
RC_PERF_ANALYSIS=$?
[ "$RC_PERF_ANALYSIS" -ne 0 ] && RC=$RC_PERF_ANALYSIS

echo "界面断言返回码 = $RC"
exit $RC
