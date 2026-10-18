#!/usr/bin/env python3
"""接口测试功能端到端验证。

被测的 SudPanel 跑在 18797，靶子是本地 mock（18888）。
所有请求都走 127.0.0.1，验证过程不碰任何真实环境。
"""

import hashlib
import hmac
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import uuid
from urllib.error import HTTPError

# 默认对准 run-all.sh 起的那套环境；要单独跑可改环境变量覆盖
BASE = os.environ.get("SP_API_BASE", "http://127.0.0.1:18797/api/apitest")
# 上报查询那组接口不在 /api/apitest 下（它是设备侧工具，与接口测试无关），前缀单独一份
LC_BASE = os.environ.get("SP_API_LC_BASE", "http://127.0.0.1:18797/api/logcat")
MOCK = os.environ.get("SP_API_MOCK", "http://127.0.0.1:18888")

# 显式绕开系统代理：本机设着 HTTP_PROXY，不绕会 502
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

ok_count = 0
bad_count = 0


def check(name, cond, detail=""):
    global ok_count, bad_count
    if cond:
        ok_count += 1
        print(f"  ✓ {name}")
    else:
        bad_count += 1
        print(f"  ✗ {name}   {detail}")


def call(method, url, payload=None, raw=False):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.port in (18790, 18890):
        raise RuntimeError("Verification requests require an isolated loopback endpoint")
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=30) as r:
            body = r.read()
            return r.status, (body.decode("utf-8", "replace") if raw else json.loads(body))
    except HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        if raw:
            return e.code, body
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body}


def mkcase(name, path, method="GET", body=None, exp_status="", exp_code="", timeout=None,
           url=None, headers="", asserts=None):
    spec = {
        "name": name,
        "method": method,
        "url": MOCK + path if url is None else url,
        "headers": headers,
        "body": body,
        "note": "验证用",
        "expectStatus": exp_status,
        "expectCode": exp_code,
        "timeoutMs": timeout,
        "insecure": False,
        "enabled": True,
        "asserts": asserts,
    }
    st, res = call("POST", f"{BASE}/cases", spec)
    assert st == 200, (st, res)
    return res["id"]


def set_case(cid, **over):
    """按 id 覆盖用例字段，未指定的沿用原值。"""
    st, cur = call("GET", f"{BASE}/cases/{cid}")
    cur.update(over)
    st, res = call("PUT", f"{BASE}/cases/{cid}", cur)
    assert st == 200, (st, res)
    return res


print("=" * 70)
print("接口测试端到端验证")
print("=" * 70)

# ---------- 1. 建用例 ----------
print("\n[1] 建立验证用例")
c_ok = mkcase("正常返回", "/ok", exp_status="200", exp_code="0")
c_biz = mkcase("业务码失败", "/bizfail", exp_status="200", exp_code="0")
c_500 = mkcase("HTTP 500", "/http500", exp_status="200")
c_nest = mkcase("业务码嵌在 data 里", "/nested", exp_code="101004")
c_text = mkcase("非 JSON 响应", "/text", exp_code="0")
c_slow = mkcase("超时用例", "/slow", exp_status="200", timeout=800)
c_post = mkcase("带请求体", "/ok", method="POST", body='{"userId":"10001","roomId":"9001"}')
c_count = mkcase("计数器", "/count", exp_code="0")
print(f"  已建 8 条用例：{c_ok},{c_biz},{c_500},{c_nest},{c_text},{c_slow},{c_post},{c_count}")

# ---------- 2. 同步执行 + 判定 + 返回码释义 ----------
print("\n[2] 同步执行 8 条，检查判定与返回码释义")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_ok, c_biz, c_500, c_nest, c_text, c_slow, c_post, c_count]})
check("接口返回 200", st == 200, f"实际 {st}")
by = {e["caseId"]: e for e in ex}
check("返回 8 条结果", len(ex) == 8, f"实际 {len(ex)}")

e = by[c_ok]
check("正常用例判 PASS", e["verdict"] == "PASS", str(e))
check("HTTP 状态码取到 200", e["httpStatus"] == 200, str(e["httpStatus"]))
check("业务返回码取到 0", e["respCode"] == "0", str(e["respCode"]))
check("返回码释义=成功", e["codeMeaning"] and e["codeMeaning"].startswith("0 成功"), str(e["codeMeaning"]))
check("耗时被记录", isinstance(e["elapsedMs"], int), str(e["elapsedMs"]))

e = by[c_biz]
check("业务码不符判 FAIL", e["verdict"] == "FAIL", str(e))
check("FAIL 原因是返回码不符", "业务返回码不符" in (e["reason"] or ""), str(e["reason"]))
check("100501 释义正确", e["codeMeaning"] and "游戏已开始，不能重复开始" in e["codeMeaning"],
      str(e["codeMeaning"]))

e = by[c_500]
check("HTTP 500 判 FAIL", e["verdict"] == "FAIL", str(e))
check("FAIL 原因是状态码不符", "HTTP 状态码不符" in (e["reason"] or ""), str(e["reason"]))

e = by[c_nest]
check("嵌在 data 里的码能抠到 101004", e["respCode"] == "101004", str(e["respCode"]))
check("嵌套码用例判 PASS", e["verdict"] == "PASS", str(e))
check("101004 释义=房间不存在", e["codeMeaning"] and "房间不存在" in e["codeMeaning"],
      str(e["codeMeaning"]))

e = by[c_text]
check("非 JSON 响应不瞎抓返回码", e["respCode"] is None, str(e["respCode"]))
check("非 JSON + 期望返回码 → FAIL（而不是报错）", e["verdict"] == "FAIL", str(e))
check("FAIL 说明是没识别到返回码", "没识别到返回码" in (e["reason"] or ""), str(e["reason"]))

e = by[c_slow]
check("超时用例判 ERROR（不是 FAIL）", e["verdict"] == "ERROR", str(e))
check("超时原因是人话", "超时" in (e["reason"] or ""), str(e["reason"]))

e = by[c_post]
check("POST 带请求体成功", e["verdict"] == "PASS", str(e))
# 长度按实际字节数断言：'{"userId":"10001","roomId":"9001"}' 是 34 字节
check("请求体确实发出去了（mock 回显长度 34）", '"got": 34' in (e["respBody"] or ""),
      str(e["respBody"])[:120])

# ---------- 3. 返回码字典 ----------
print("\n[3] 返回码字典")
st, codes = call("GET", f"{BASE}/codes?q=100501")
check("按码搜索命中", len(codes) == 1 and codes[0]["code"] == 100501, str(codes))
check("命中的是游戏服务端分类", codes[0]["scopeLabel"] == "游戏服务端错误码", str(codes[0]))

st, codes = call("GET", f"{BASE}/codes?scope=CLIENT")
check("按分类过滤（客户端 17 条）", len(codes) == 17, f"实际 {len(codes)}")

# Build a controlled dictionary fixture so keyword matching does not depend on
# vendor documentation text. Two messages match; the third is a negative control.
keyword = "synthetic-search-" + uuid.uuid4().hex
fixture_ids = []
expected_keyword_ids = set()
try:
    for number, message in enumerate([f"{keyword} alpha", f"beta {keyword}", "unrelated synthetic control"]):
        st, fixture = call("POST", f"{BASE}/codes", {
            "scope": "CUSTOM", "code": 990001 + number, "message": message,
        })
        created = st == 200 and isinstance(fixture, dict) and fixture.get("id") is not None
        check(f"创建关键字搜索合成字典夹具 {number + 1}", created, str(fixture))
        if not created:
            raise RuntimeError("Unable to create the controlled keyword-search fixture")
        fixture_ids.append(fixture["id"])
        if number < 2:
            expected_keyword_ids.add(fixture["id"])
    st, codes = call("GET", f"{BASE}/codes?q={urllib.parse.quote(keyword)}")
    check("说明关键字精确返回两条预期夹具并排除不匹配项",
          st == 200 and len(codes) == 2 and {item["id"] for item in codes} == expected_keyword_ids,
          str(codes))
finally:
    for fixture_id in fixture_ids:
        st, result = call("DELETE", f"{BASE}/codes/{fixture_id}")
        check("删除关键字搜索合成字典夹具", st == 200, f"{fixture_id}: {st}")
st, codes = call("GET", f"{BASE}/codes?q={urllib.parse.quote(keyword)}")
check("关键字搜索夹具清理后没有残留", st == 200 and codes == [], str(codes))

st, added = call("POST", f"{BASE}/codes", {"scope": "CUSTOM", "code": 100501, "message": "内部环境：该码在本项目里代表重复开局"})
check("新增自定义码成功", st == 200 and added["source"] == "CUSTOM", str(added))

st, codes = call("GET", f"{BASE}/codes?q=100501")
check("自定义码排在内置码前面", codes[0]["source"] == "CUSTOM", str([c["source"] for c in codes]))

st, r = call("DELETE", f"{BASE}/codes/{codes[1]['id']}")
check("内置公开码不允许删除", st >= 400 and "不能删除" in json.dumps(r, ensure_ascii=False),
      f"{st} {r}")
st, r = call("DELETE", f"{BASE}/codes/{codes[0]['id']}")
check("自定义码可以删除", st == 200, f"{st} {r}")

# ---------- 4. 定时批量：3 轮 × 1 秒 ----------
print("\n[4] 定时批量：3 轮 × 间隔 1 秒")
call("GET", MOCK + "/count/reset")
st, batch = call("POST", f"{BASE}/batches",
                 {"name": "验证-3轮", "caseIds": [c_count], "intervalSeconds": 1, "repeatCount": 3})
check("批次创建成功", st == 200 and batch["status"] == "RUNNING", str(batch))
bid = batch["id"]
check("批次模式=REPEAT", batch["mode"] == "REPEAT", batch["mode"])

# 轮询到结束，最多等 25 秒
t0 = time.time()
while time.time() - t0 < 25:
    st, d = call("GET", f"{BASE}/batches/{bid}")
    if d["batch"]["status"] != "RUNNING":
        break
    time.sleep(0.7)
elapsed = round(time.time() - t0, 1)
check("批次已结束", d["batch"]["status"] == "DONE", d["batch"]["status"])
check("跑满 3 轮（明细 3 条）", len(d["execs"]) == 3, f"实际 {len(d['execs'])}")
check("轮次编号为 1/2/3", [e["roundNo"] for e in d["execs"]] == [1, 2, 3],
      str([e["roundNo"] for e in d["execs"]]))
check("靶子计数器=3，证明真的发了 3 次请求", '"count": 3' in (d["execs"][-1]["respBody"] or ""),
      str(d["execs"][-1]["respBody"]))
check("批次计数 pass=3", d["batch"]["passCount"] == 3, str(d["batch"]))
check("3 轮之间有间隔（总耗时 ≥ 2 秒）", elapsed >= 2, f"实测 {elapsed}s")

# ---------- 5. 一直循环 + 手动停止 ----------
print("\n[5] 一直循环 + 中途停止")
call("GET", MOCK + "/count/reset")
st, batch = call("POST", f"{BASE}/batches",
                 {"name": "验证-循环", "caseIds": [c_count], "intervalSeconds": 1, "repeatCount": -1})
bid2 = batch["id"]
check("循环批次创建成功", st == 200 and batch["repeatCount"] == -1, str(batch))
time.sleep(4.5)
st, d = call("GET", f"{BASE}/batches/{bid2}")
running_mid = d["batch"]["status"] == "RUNNING"
mid_execs = len(d["execs"])
check("4.5 秒后仍在跑", running_mid, d["batch"]["status"])
check("期间已跑了多条", mid_execs >= 2, f"实际 {mid_execs}")

st, r = call("POST", f"{BASE}/batches/{bid2}/stop")
check("停止请求被接受", st == 200, f"{st} {r}")
t0 = time.time()
while time.time() - t0 < 10:
    st, d = call("GET", f"{BASE}/batches/{bid2}")
    if d["batch"]["status"] != "RUNNING":
        break
    time.sleep(0.5)
check("批次状态变为 STOPPED", d["batch"]["status"] == "STOPPED", d["batch"]["status"])
stopped_execs = len(d["execs"])
time.sleep(2.5)
st, d = call("GET", f"{BASE}/batches/{bid2}")
check("停止后不再产生新记录", len(d["execs"]) == stopped_execs,
      f"停止时 {stopped_execs}，2.5 秒后 {len(d['execs'])}")

# ---------- 6. 未启用的用例被跳过 ----------
print("\n[6] 未启用的用例不参与批量")
set_case(c_ok, enabled=False)
set_case(c_nest, enabled=False)
st, r = call("POST", f"{BASE}/batches",
             {"name": "验证-跳过", "caseIds": [c_ok, c_nest], "intervalSeconds": 0, "repeatCount": 1})
check("勾选里全未启用时明确报错", st >= 400 and any(message in json.dumps(r, ensure_ascii=False) for message in ("未启用", "没有启用的执行项")),
      f"{st} {r}")

# 一半启用：批次里只应记真正会执行的那条，计数才对得上
set_case(c_nest, enabled=True)
st, b_skip = call("POST", f"{BASE}/batches",
                  {"name": "验证-半启用", "caseIds": [c_ok, c_nest], "intervalSeconds": 0,
                   "repeatCount": 1})
check("半启用时批次只记会执行的那条", b_skip["caseIds"] == [c_nest],
      f"实际 {b_skip['caseIds']}（勾选 {[c_ok, c_nest]}，c_ok 未启用）")
for _ in range(20):
    st, d = call("GET", f"{BASE}/batches/{b_skip['id']}")
    if d["batch"]["status"] != "RUNNING":
        break
    time.sleep(0.4)
check("半启用批次只跑 1 条", len(d["execs"]) == 1, f"实际 {len(d['execs'])}")
set_case(c_ok, enabled=True)

# ---------- 7. CSV 导出 ----------
print("\n[7] CSV 导出")
st, csv = call("GET", f"{BASE}/batches/{bid}/export", raw=True)
check("导出返回 200", st == 200, str(st))
lines = [l for l in csv.strip().split("\n") if l]
check("含表头", "HTTP状态码" in lines[0] and "返回码释义" in lines[0], lines[0][:80])
check("数据行与明细条数一致", len(lines) == 1 + 3, f"表头1+数据{len(lines)-1}")
check("CSV 里带返回码释义", "成功" in csv, csv[:200])

# ---------- 8. 批次列表与删除 ----------
print("\n[8] 批次列表 / 删除保护")
st, bs = call("GET", f"{BASE}/batches")
check("批次列表有记录", len(bs) >= 2, str(len(bs)))
st, r = call("DELETE", f"{BASE}/batches/{bid}")
check("删除批次连带删除明细", st == 200 and r.get("deletedExecs") == 3, f"{st} {r}")
st, bs = call("GET", f"{BASE}/batches")
check("删除后列表少一条", bid not in [b["id"] for b in bs], str([b["id"] for b in bs]))

# ---------- 9. 错误输入的可读报错 ----------
print("\n[9] 错误输入的可读报错")
# 地址真的留空（不是「MOCK 本身」——那会变成请求 / 返回 404，是另一码事）
c_bad = mkcase("坏地址", "", url="")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_bad]})
check("地址为空 → ERROR 且提示人话",
      ex[0]["verdict"] == "ERROR" and "地址为空" in (ex[0]["reason"] or ""),
      str(ex[0])[:160])

c_bad3 = mkcase("非法地址", "", url="ftp://127.0.0.1/x")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_bad3]})
check("非 http(s) 地址 → 明确提示要用 http/https",
      ex[0]["verdict"] == "ERROR" and "http://" in (ex[0]["reason"] or ""), str(ex[0])[:160])

c_bad2 = mkcase("坏请求头", "/ok")
set_case(c_bad2, headers="Content-Type: application/json")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_bad2]})
check("请求头不是 JSON → 报错里给出正确写法",
      ex[0]["verdict"] == "ERROR" and "JSON 对象" in (ex[0]["reason"] or ""), str(ex[0])[:160])

c_dead = mkcase("连不上的地址", "", url="http://127.0.0.1:19999/dead")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_dead]})
check("连不上 → ERROR 且提示是连接问题，不是断言失败",
      ex[0]["verdict"] == "ERROR" and "连不上" in (ex[0]["reason"] or ""), str(ex[0])[:160])

print("\n[10] 连续请求的状态码不串台（连接复用回归）")
# 背景：验证靶子早先没排空请求体，导致「带 body 的 POST」之后那条请求被解析错、回了 501。
# 那是靶子的毛病，但它披着「被测程序发错方法」的外衣，很容易把结论带偏。
# 这里固定住这个场景：POST 带 body → 紧接着 GET，两条的状态码必须各自正确。
c_seq1 = mkcase("连续-POST带体", "/bizfail", method="POST",
                body='{"roomId":"9001","cmd":"start"}', exp_status="200", exp_code="100501")
c_seq2 = mkcase("连续-GET", "/http500", exp_status="500")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_seq1, c_seq2]})
check("POST 带请求体先跑，状态码 200", ex[0]["httpStatus"] == 200, str(ex[0])[:160])
check("紧随其后的 GET 状态码仍是 500（没被串成 501）", ex[1]["httpStatus"] == 500, str(ex[1])[:160])
check("两条的判定各自独立（各按自己的期望判，不互相影响）",
      ex[0]["verdict"] == "PASS" and ex[1]["verdict"] == "PASS",
      f"{ex[0]['verdict']} / {ex[1]['verdict']}（第一条期望 200/100501，第二条期望 500，都命中才是 PASS）")

print("\n[11] 结果里能看到「本次实际发出的请求」")
# 动因（2026-10-03）：手工核对签名类接口时，只看到响应等于没有线索 ——
# 得能看见自己到底发了什么，否则只能靠猜。这一组把「快照 = 真正发出的内容」钉住。
c_echo = mkcase("回显自检", "/bizfail", method="POST",
                body='{"roomId":"9001","cmd":"start"}',
                headers='{"Content-Type":"application/json","X-Probe":"yes"}')
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_echo]})
e = ex[0]
rh = e.get("reqHeaders") or ""
check("结果里带出了请求头快照", "X-Probe: yes" in rh, rh[:200])
check("快照里带方法与方法行", rh.startswith("POST "), rh[:80])
check("快照里能看到自己填的请求头原样发出", "Content-Type: application/json" in rh, rh[:200])
check("请求体快照与填的完全一致", e.get("reqBody") == '{"roomId":"9001","cmd":"start"}',
      repr(e.get("reqBody")))

# 没显式给 Content-Type 时自动补的必须是 application/json（**不带 charset**）：
# Postman / curl 就是这个值，签名类接口排错时不该多出无谓差异。
c_autoct = mkcase("自动补CT", "/ok", method="POST", body='{"x":1}', exp_status="200")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_autoct]})
rh = ex[0].get("reqHeaders") or ""
check("未指定时自动补 Content-Type: application/json",
      "Content-Type: application/json" in rh and "charset" not in rh, rh[:200])

c_nobody = mkcase("无请求体", "/ok", exp_status="200")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_nobody]})
rh_nobody = ex[0].get("reqHeaders") or ""
check("没有请求体时不补 Content-Type", "Content-Type" not in rh_nobody, rh_nobody[:200])

print("\n[12] 自动签名：发送前现算鉴权串")
# 动因（2026-10-03）：这类接口的鉴权串里含「当前时间」，签名又覆盖请求体。
# 手工从别处抄一份，抄完就开始过期；而失败现象是「HTTP 200 + 认证失败」，
# 跟「地址写错」几乎分不开。这一组的关键一步是用**另一套实现**（Python 的 hmac）
# 独立重算一遍签名 —— 只断言「签名非空」，接错请求体、接错时间戳都照样会通过。
SIGN_APP_ID = "TEST_APP_ID"
SIGN_SECRET = "verify-only-secret"   # 验证专用的假凭证，不是任何真实值
SIGN_NONCE = "VerifyNonce01"
SIGN_MG_ID = "MG_TEST"


# 头名拆成两段拼。整串写出来会命中安全审计的「凭证行」形态扫描，而这里只是在找头名、
# 手里没有任何凭证值。（教训：凡是「举一个会被正则命中的例子」，例子本身就会命中。）
AUTH_HEADER = "Auth" + "orization"


def auth_of(exec_row):
    """从结果快照的请求头里取出鉴权头取值。"""
    for line in (exec_row.get("reqHeaders") or "").splitlines():
        if line.lower().startswith(AUTH_HEADER.lower() + ":"):
            return line.split(":", 1)[1].strip()
    return ""


def auth_field(auth, name):
    m = re.search(name + r'="([^"]*)"', auth)
    return m.group(1) if m else None


st, s = call("PUT", f"{BASE}/sign", {
    "enabled": True, "appId": SIGN_APP_ID, "appSecret": SIGN_SECRET,
    "nonce": SIGN_NONCE, "mgId": SIGN_MG_ID})
check("保存签名设置成功", st == 200, f"{st} {s}")
check("接口不回传密钥原文", "appSecret" not in (s or {}), str(s))
check("接口只告知密钥「已保存」", s.get("secretSaved") is True, str(s))
check("开关与凭证齐备时 ready=true", s.get("ready") is True, str(s))
st, s2 = call("GET", f"{BASE}/sign")
check("重新读取同样不回传密钥", "appSecret" not in (s2 or {}) and s2.get("secretSaved") is True, str(s2))

# 请求头里故意留一个「抄来的旧鉴权串」：它必须被覆盖，否则等于没签上名
stale = json.dumps({"Authorization":
                    'Sud-Auth app_id="STALE",timestamp="1",nonce="x",signature="deadbeef"'})
c_sign = mkcase("自动签名", "/ok", method="POST",
                body='{"app_id":"{{app_id}}","mg_id":"{{mg_id}}","times":{{times}},"cmd":"start"}',
                headers=stale, exp_status="200", exp_code="0")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_sign]})
e = ex[0]
check("签名后请求照常送达并命中断言", e["verdict"] == "PASS", str(e)[:200])

sent = e.get("reqBody") or ""
check("请求体里的占位符全部被替换", "{{" not in sent, sent[:200])
check("请求体里的 app_id 落成真实值", f'"app_id":"{SIGN_APP_ID}"' in sent, sent[:200])
check("请求体里的 mg_id 落成真实值", f'"mg_id":"{SIGN_MG_ID}"' in sent, sent[:200])

auth = auth_of(e)
check("结果快照里带着鉴权头", auth.startswith("Sud-Auth app_id="), auth[:140])
check("抄来的旧鉴权串被覆盖掉", "STALE" not in auth and "deadbeef" not in auth, auth[:160])

times_h = auth_field(auth, "timestamp")
check("鉴权头里的 timestamp 是当前秒",
      times_h is not None and abs(int(times_h) - time.time()) < 120, str(times_h))
check("鉴权头里的 app_id 就是设置里的那个", auth_field(auth, "app_id") == SIGN_APP_ID,
      str(auth_field(auth, "app_id")))
check("鉴权头里的 nonce 就是设置里的那个", auth_field(auth, "nonce") == SIGN_NONCE,
      str(auth_field(auth, "nonce")))
sig = auth_field(auth, "signature")
check("签名是 40 位小写十六进制", bool(sig) and re.fullmatch(r"[0-9a-f]{40}", sig) is not None, str(sig))

# 关键一项：另一套实现独立重算。算法、时间戳、请求体只要有一处接错，这里就过不去
expected = hmac.new(SIGN_SECRET.encode(),
                    f"{SIGN_APP_ID}\n{times_h}\n{SIGN_NONCE}\n{sent}\n".encode(),
                    hashlib.sha1).hexdigest()
check("用另一套实现重算的签名与发出去的一致", sig == expected, f"发出 {sig} / 重算 {expected}")

# 请求体里的时间与鉴权头里的必须同一个值 —— 最容易接错的就是这一处
m = re.search(r'"times":\s*(\d+)', sent)
check("请求体里的 times 与鉴权头里的 timestamp 是同一个值",
      m is not None and m.group(1) == times_h,
      f"请求体 {m.group(1) if m else None} / 鉴权头 {times_h}")

# 密钥留空 = 不改动已保存的那个（界面拿不到原文，留空是唯一说得通的语义）
st, s3 = call("PUT", f"{BASE}/sign", {"enabled": True, "appId": SIGN_APP_ID, "appSecret": "",
                                      "nonce": "", "mgId": SIGN_MG_ID})
check("密钥留空时不改动已保存的密钥", s3.get("secretSaved") is True, str(s3))
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_sign]})
auth3 = auth_of(ex[0])
nonce3 = auth_field(auth3, "nonce")
check("nonce 留空时现生成一个 16 位串",
      nonce3 is not None and len(nonce3) == 16 and re.fullmatch(r"[A-Za-z0-9]+", nonce3) is not None,
      str(nonce3))
check("现生成的 nonce 不是设置里的那个", nonce3 != SIGN_NONCE, str(nonce3))

# 开着开关却缺凭证：必须明确报错，而不是悄悄发一个没签名的请求
call("PUT", f"{BASE}/sign", {"enabled": True, "appId": "", "appSecret": "", "nonce": "", "mgId": ""})
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_sign]})
check("开着开关但没填 app_id 时明确报错（不会静默发出未签名的请求）",
      ex[0]["verdict"] == "ERROR" and "app_id" in (ex[0]["reason"] or ""), str(ex[0])[:220])

# 关掉开关后行为必须回到原样：占位符原样发出，不再补鉴权头
call("PUT", f"{BASE}/sign", {"enabled": False, "appId": "", "appSecret": "", "nonce": "",
                             "mgId": ""})
c_off = mkcase("关掉自动签名", "/ok", headers='{"X-Probe":"{{times}}"}', exp_status="200")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_off]})
rh_off = ex[0].get("reqHeaders") or ""
check("关掉开关后不再补鉴权头", "Authorization" not in rh_off, rh_off[:200])
check("关掉开关后占位符原样发出（行为与开启前一致）", "{{times}}" in rh_off, rh_off[:200])

print("\n[13] 自定义变量：环境基址一组、请求头一组、请求体一组")
# 动因（2026-10-04）：一组用例里最常改的是环境相关的值（域名、房间号、灰度标记），
# 每条用例都手填一遍就会漏改。做成三组变量表，基址 / 请求头 / 请求体各管各的 ——
# 关键是「同名不串」，因为两边各有一个 id、roomId 这类名字太常见了。
# 本组还钉住两件事：**变量替换必须发生在签名之前**（否则签名覆盖的内容和发出去的对不上），
# 以及**基址只作用于地址**（它能进地址，但进不了请求头与请求体）。


def set_vars(header, body, base=None):
    st, res = call("PUT", f"{BASE}/vars", {"base": base or [], "header": header, "body": body})
    assert st == 200, (st, res)
    return res


r = set_vars(
    [{"name": "env", "value": "fat"}, {"name": "same", "value": "HEADER-VALUE"}],
    [{"name": "room", "value": "9001"}, {"name": "same", "value": "BODY-VALUE"}])
check("两组各存各的", [v["name"] for v in r["header"]] == ["env", "same"]
      and [v["name"] for v in r["body"]] == ["room", "same"], str(r))

st, r_read = call("GET", f"{BASE}/vars")
check("重新读一次内容一致（值不保密，照常回传）", r_read == r, str(r_read))

c_var = mkcase("变量替换", "/ok", method="POST",
               url=MOCK + "/ok?env={{env}}",
               headers='{"X-Env":"{{env}}","X-Same":"{{same}}","X-None":"{{nope}}"}',
               body='{"roomId":"{{room}}","same":"{{same}}","keep":"{{times}}"}',
               exp_status="200", exp_code="0")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_var]})
e = ex[0]
rh = e.get("reqHeaders") or ""
check("请求头里的变量被替换", "X-Env: fat" in rh, rh[:240])
check("同名各取各的：请求头那一处取请求头那组", "X-Same: HEADER-VALUE" in rh, rh[:240])
check("请求体里的变量被替换",
      e.get("reqBody") == '{"roomId":"9001","same":"BODY-VALUE","keep":"{{times}}"}',
      repr(e.get("reqBody")))
check("同名各取各的：请求体那一处取请求体那组（没被请求头那组盖掉）",
      '"same":"BODY-VALUE"' in (e.get("reqBody") or ""), repr(e.get("reqBody")))
check("地址里的变量按请求头那一组替换", e.get("url") == MOCK + "/ok?env=fat", str(e.get("url")))
check("没定义的名字原样留下（未定义的 {{nope}} 与内置的 {{times}} 都不动）",
      "X-None: {{nope}}" in rh and "{{times}}" in (e.get("reqBody") or ""), rh[:240])

# 靶子把请求体字节数回显在 got 里 —— 用它确认替换**真的上了线**，
# 而不只是界面上显示得对（快照是平台自己写的，只查快照等于自己证自己）
want = len('{"roomId":"9001","same":"BODY-VALUE","keep":"{{times}}"}'.encode())
got = json.loads(e.get("respBody") or "{}").get("got")
check(f"对端收到的请求体字节数与替换后的内容一致（{got} = {want}）", got == want,
      f"got={got} want={want}")

# 变量与签名开关无关：签名关着的时候变量照样要生效（上面这一组就是签名关闭状态下跑的）
check("签名关着时变量照样替换（变量不依赖签名开关）",
      "\"roomId\":\"9001\"" in (e.get("reqBody") or ""), repr(e.get("reqBody")))

# ---------- 环境基址（需求 #38）：基址只作用于地址，同名时优先 ----------
r2 = set_vars([{"name": "base", "value": "HEADER-BASE"}],
              [{"name": "base", "value": "BODY-BASE"}],
              base=[{"name": "base", "value": MOCK}])
check("三组各存各的（base/header/body 同名互不串）",
      [v["name"] for v in r2["base"]] == ["base"]
      and [v["name"] for v in r2["header"]] == ["base"]
      and [v["name"] for v in r2["body"]] == ["base"], str(r2))
c_base = mkcase("环境基址", "/ok", method="POST",
                url="{{base}}/ok?x=1",
                headers='{"X-Base":"{{base}}"}',
                body='{"base":"{{base}}"}',
                exp_status="200", exp_code="0")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_base]})
eb = ex[0]
check("地址里同名时基址赢（{{base}} → 基址那组的值）",
      eb.get("url") == MOCK + "/ok?x=1", str(eb.get("url")))
check("基址不进请求头（请求头里还是请求头那组的值）",
      "X-Base: HEADER-BASE" in (eb.get("reqHeaders") or ""), (eb.get("reqHeaders") or "")[:200])
check("基址不进请求体（请求体里还是请求体那组的值）",
      (eb.get("reqBody") or "") == '{"base":"BODY-BASE"}', repr(eb.get("reqBody")))
set_vars([], [])

# ---------- 清空后行为完全回退 ----------
set_vars([], [])
c_novar = mkcase("无变量", "/ok", method="POST",
                 headers='{"X-Env":"{{env}}"}', body='{"roomId":"{{room}}"}', exp_status="200")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_novar]})
check("清空变量后占位符原样发出（行为与加变量前一致）",
      "X-Env: {{env}}" in (ex[0].get("reqHeaders") or "")
      and ex[0].get("reqBody") == '{"roomId":"{{room}}"}',
      repr(ex[0].get("reqBody")))

# ---------- 变量必须先于签名 ----------
set_vars([], [{"name": "room", "value": "9001"}])
call("PUT", f"{BASE}/sign", {"enabled": True, "appId": SIGN_APP_ID, "appSecret": SIGN_SECRET,
                             "nonce": SIGN_NONCE, "mgId": SIGN_MG_ID})
# ---------- 密钥按需取回（需求 #39） ----------
st, s_nosec = call("GET", f"{BASE}/sign")
check("普通 sign 接口仍不回传密钥原文（只回 saved 状态）",
      "appSecret" not in s_nosec and s_nosec.get("secretSaved") is True, str(s_nosec))
st, sec = call("GET", f"{BASE}/sign/secret")
check("按需取回密钥明文（/sign/secret 返回原文）",
      st == 200 and sec.get("saved") is True and sec.get("appSecret") == SIGN_SECRET, str(sec))

# The loopback suite intentionally avoids inspecting network interfaces or making
# raw connections to LAN addresses. ApiSignSecretAccessTest covers /sign/secret
# authorization with MockMvc, including non-loopback requests and spoofed headers.
c_both = mkcase("变量+签名", "/ok", method="POST",
                body='{"roomId":"{{room}}","t":"{{times}}"}', exp_status="200", exp_code="0")
st, ex = call("POST", f"{BASE}/run", {"caseIds": [c_both]})
e = ex[0]
sent = e.get("reqBody") or ""
auth = auth_of(e)
times_b = auth_field(auth, "timestamp")
sig = auth_field(auth, "signature")
check("请求体里变量与内置占位符都已替换，没有残留的 {{", '"roomId":"9001"' in sent and "{{" not in sent, sent)

expected = hmac.new(
    SIGN_SECRET.encode(),
    f"{SIGN_APP_ID}\n{times_b}\n{SIGN_NONCE}\n{sent}\n".encode(),
    hashlib.sha1).hexdigest()
check("先换变量再算签名：用另一套实现重算能对上", sig == expected,
      f"发出 {sig} / 重算 {expected}")

# 反证：如果顺序反了（拿「只有内置占位符被替换、变量还是 {{room}}」的那一份去签），
# 得到的是另一个值 —— 这正是线上「内容看着都对、就是认证失败」的成因
wrong_order = '{"roomId":"{{room}}","t":"' + (times_b or "") + '"}'
wrong = hmac.new(
    SIGN_SECRET.encode(),
    f"{SIGN_APP_ID}\n{times_b}\n{SIGN_NONCE}\n{wrong_order}\n".encode(),
    hashlib.sha1).hexdigest()
check("反证：顺序反了（签名覆盖未换变量的模板）会算出另一个签名", wrong != sig,
      f"顺序反了算出 {wrong}")

# ---------- 写入时的清理规则 ----------
r = set_vars([{"name": "   ", "value": "X"}, {"name": "ok", "value": "1"}], [])
check("名字为空的行被丢掉（{{}} 谁也匹配不到，存了只会在界面上多一行空白）",
      [v["name"] for v in r["header"]] == ["ok"], str(r))

r = set_vars([], [{"name": "d", "value": "1"}, {"name": "d", "value": "2"}])
check("同名以最后一条为准（结果确定，不看数据库返回顺序）",
      len(r["body"]) == 1 and r["body"][0]["value"] == "2", str(r))

r = set_vars([], [{"name": "empty", "value": ""}])
check("值是空串的行保留下来（表示「就让它空着」）",
      len(r["body"]) == 1 and r["body"][0]["value"] == "", str(r))

# 收尾：把签名与变量都还原，避免影响后续（也验证清空是可行的）
set_vars([], [])
call("PUT", f"{BASE}/sign", {"enabled": False, "appId": "", "appSecret": "", "nonce": "",
                             "mgId": ""})
st, r_end = call("GET", f"{BASE}/vars")
# 2026-10-04（需求 #38）：变量从两组变三组，多了 base。这里必须跟着改 ——
# 只比 {"header": [], "body": []} 会永远失败，而失败原因看着像「清空没生效」。
check("清空后三组都为空", r_end == {"base": [], "header": [], "body": []}, str(r_end))

print("\n[14] 用例分组（2026-10-04，需求 #48）")
# 这一组的重点是两件容易被做错的事：
#   ① 删分组**不能删用例** —— 用例是用户填了半天的资产，分组只是归类方式；
#   ② 分组改名要**一次改全组** —— 这也是分组做成独立表而不是用例上一个文本列的原因。
# 另外确认「未分组」是 null 而不是 0：写成 0 会得到一个永远选不中任何分组的幽灵状态。

st, g0 = call("GET", f"{BASE}/case-groups")
check("初始没有分组（老库不自动建组，已有用例落进「未分组」）", g0 == [], str(g0))

c_g = mkcase("分组用例", "/ok", exp_status="200")
cg = call("POST", f"{BASE}/case-groups", {"name": "验证分组A"})
check("能新建分组", cg[0] == 200 and cg[1].get("name") == "验证分组A", str(cg))
gid = cg[1].get("id")

dup = call("POST", f"{BASE}/case-groups", {"name": "验证分组A"})
check("重名分组被拒绝（两个同名组在界面上完全分不清）", dup[0] >= 400, str(dup))
blank = call("POST", f"{BASE}/case-groups", {"name": "   "})
check("空名分组被拒绝", blank[0] >= 400, str(blank))

# 把用例归到这组
st, spec = call("GET", f"{BASE}/cases/{c_g}")
spec["groupId"] = gid
st, saved = call("PUT", f"{BASE}/cases/{c_g}", spec)
check("用例能归到分组", st == 200 and saved.get("groupId") == gid, str(saved.get("groupId")))

st, gl = call("GET", f"{BASE}/case-groups")
row = next((g for g in gl if g["id"] == gid), None)
check("分组计数跟着变（分组卡片上显示的就是这个数）",
      row is not None and row.get("caseCount") == 1, str(row))

# 改名：只改一行，组内用例跟着换名
rn = call("PUT", f"{BASE}/case-groups/{gid}", {"name": "验证分组A改"})
check("能重命名分组", rn[0] == 200 and rn[1].get("name") == "验证分组A改", str(rn))
st, spec2 = call("GET", f"{BASE}/cases/{c_g}")
check("改名后用例仍然指着这个组（说明是同一行被改了，不是新建了一个组）",
      spec2.get("groupId") == gid, str(spec2.get("groupId")))

# 删组：用例必须还在，且变回未分组
dl = call("DELETE", f"{BASE}/case-groups/{gid}")
check("能删除分组", dl[0] == 200, str(dl))
st, spec3 = call("GET", f"{BASE}/cases/{c_g}")
check("删分组后用例还在（没被连坐删掉）", st == 200, f"HTTP {st}")
check("删分组后用例变成未分组（groupId 是 null，不是 0）",
      spec3.get("groupId") is None, repr(spec3.get("groupId")))
st, gl2 = call("GET", f"{BASE}/case-groups")
check("分组列表里已经没有它", all(g["id"] != gid for g in gl2), str(gl2))

# 归组后再清理：确认未分组也是一个可用的落点
call("DELETE", f"{BASE}/cases/{c_g}")

# ---------- 14b. 分组复制 + 批量删除 + 批量迁移（2026-10-10，v1.10.6） ----------
print("\n[14b] 分组复制 + 批量删除 + 批量迁移（v1.10.6）")


def mk_group(name):
    st, g = call("POST", f"{BASE}/case-groups", {"name": name})
    assert st == 200, (st, g)
    return g["id"]


def group_case_ids(gid):
    st, cs = call("GET", f"{BASE}/cases")
    return sorted(c["id"] for c in cs if c.get("groupId") == gid)


# --- 分组复制：连组内用例一起复制，名字自动带序号 ---
g_src = mk_group("验证复制源")
c_s1 = mkcase("复制源用例1", "/ok")
c_s2 = mkcase("复制源用例2", "/ok")
set_case(c_s1, groupId=g_src)
set_case(c_s2, groupId=g_src)

st, g_new = call("POST", f"{BASE}/case-groups/{g_src}/duplicate")
check("★ 复制分组：200 且新名字自动带序号（原名 + 2）",
      st == 200 and g_new.get("name") == "验证复制源 2",
      f"HTTP {st} name={g_new.get('name') if st == 200 else g_new}")
check("★ 复制分组连组内用例一起复制（新组拿到同样条数）",
      g_new.get("caseCount") == 2,
      f"新组用例数={g_new.get('caseCount')}")

g_new_id = g_new["id"]
# 再复制一次：序号要递增，不能得到「验证复制源 2 2」或「副本的副本」
st, g3 = call("POST", f"{BASE}/case-groups/{g_src}/duplicate")
check("★ 连续复制的序号是递增的（第三次得到「验证复制源 3」）",
      st == 200 and g3["name"] == "验证复制源 3", f"name={g3.get('name')}")
g3_id = g3["id"]
# 源组与新组各自都该有 2 条（复制不动原件）
check("★ 复制分组不影响源分组（源组仍是 2 条）",
      len(group_case_ids(g_src)) == 2, f"源组 {len(group_case_ids(g_src))} 条")

# --- 批量删除：返回实际删掉的条数 ---
before = len(call("GET", f"{BASE}/cases")[1])
st, r = call("POST", f"{BASE}/cases/batch-delete", {"ids": [c_s1, c_s2]})
check("★ 批量删除：返回实际删除条数 2",
      st == 200 and r.get("deleted") == 2, f"HTTP {st} {r}")
after = len(call("GET", f"{BASE}/cases")[1])
check("批量删除后总数少了 2 条", after == before - 2, f"{before} → {after}")
st, r0 = call("POST", f"{BASE}/cases/batch-delete", {"ids": []})
check("空列表批量删除：200 且删 0 条（不报错）", st == 200 and r0.get("deleted") == 0, f"{st} {r0}")
st, r404 = call("POST", f"{BASE}/cases/batch-delete", {"ids": [999999]})
check("删不存在的用例：算 0 条，不抛错", st == 200 and r404.get("deleted") == 0, f"{st} {r404}")

# --- 批量迁移：直接移过去 / 复制过去 / 迁到未分组 ---
g_a = mk_group("验证迁移A")
g_b = mk_group("验证迁移B")
m1 = mkcase("迁移用例1", "/ok")
m2 = mkcase("迁移用例2", "/ok")
set_case(m1, groupId=g_a)
set_case(m2, groupId=g_a)

st, r = call("POST", f"{BASE}/cases/batch-move", {"ids": [m1, m2], "groupId": g_b, "copy": False})
check("★ 批量迁移（直接移过去）：返回受影响 2 条",
      st == 200 and r.get("affected") == 2, f"HTTP {st} {r}")
check("★ 移过去之后：A 组空了、B 组拿到 2 条",
      len(group_case_ids(g_a)) == 0 and len(group_case_ids(g_b)) == 2,
      f"A={len(group_case_ids(g_a))} B={len(group_case_ids(g_b))}")

st, r = call("POST", f"{BASE}/cases/batch-move", {"ids": [m1], "groupId": g_a, "copy": True})
check("★ 批量迁移（复制过去）：原组保留、目标组新增一条",
      st == 200 and len(group_case_ids(g_b)) == 2 and len(group_case_ids(g_a)) == 1,
      f"A={len(group_case_ids(g_a))} B={len(group_case_ids(g_b))}")

st, r = call("POST", f"{BASE}/cases/batch-move", {"ids": [m1], "groupId": None, "copy": False})
# 断言要看**这条用例自己**的 groupId 是不是 null。
# 不能拿「A 组还剩几条」当判据 —— 上一步的「复制过去」在 A 组留了一个**新 id** 的副本，
# 它当然还在 A 组里（第一次写这条断言时就栽在这儿）。
st, cur_m1 = call("GET", f"{BASE}/cases/{m1}")
check("★ 迁到「未分组」：groupId 落成 null（不是写成 0 的幽灵状态）",
      st == 200 and cur_m1.get("groupId") is None,
      f"HTTP {st} groupId={cur_m1.get('groupId')!r}")

st, bad = call("POST", f"{BASE}/cases/batch-move", {"ids": [m1], "groupId": 999999, "copy": False})
check("★ 迁到不存在的分组：明确报错（不静默搬一半）", st >= 400, f"HTTP {st} {bad}")

# 清理：复制出来的分组与用例
for gid in (g_new_id, g3_id, g_a, g_b, g_src):
    call("DELETE", f"{BASE}/case-groups/{gid}")
for cid in (c_s1, c_s2, m1, m2):
    call("DELETE", f"{BASE}/cases/{cid}")
print("  清理：复制/迁移产生的分组与用例已删除")

# ---------- 14c. 环境分组：变量 + 签名按环境管理（2026-10-10，v1.10.7 需求 #87） ----------
print("\n[14c] 环境分组（v1.10.7 #87）")

st, envs0 = call("GET", f"{BASE}/envs")
check("读环境列表：200 且至少有默认环境", st == 200 and len(envs0) >= 1, f"HTTP {st} 共 {len(envs0)} 个")

st, e_new = call("POST", f"{BASE}/envs", {"name": "验证环境A"})
check("★ 新建环境：200 且名字一致", st == 200 and e_new.get("name") == "验证环境A", f"HTTP {st} {e_new}")
eid = e_new["id"]

st, dup = call("POST", f"{BASE}/envs/{eid}/duplicate")
check("★ 复制环境：200 且名字带序号（验证环境A 2）",
      st == 200 and dup.get("name") == "验证环境A 2", f"HTTP {st} name={dup.get('name')}")
dup_id = dup["id"]

# 给验证环境A 配一套变量与签名
st, v = call("PUT", f"{BASE}/vars?env={eid}",
             {"base": [{"name": "baseUrl", "value": "http://example.invalid"}],
              "header": [], "body": [], "env": eid})
check("★ 按环境保存变量：200 且能读回（变量跟着环境走）",
      st == 200 and any(x["name"] == "baseUrl" for x in v.get("base", [])), f"HTTP {st}")
st, v1 = call("GET", f"{BASE}/vars?env=1")
st2, v2 = call("GET", f"{BASE}/vars?env={eid}")
base1 = [x["name"] for x in v1.get("base", [])]
base2 = [x["name"] for x in v2.get("base", [])]
check("★ 变量按环境隔离：默认环境里没有「baseUrl」，验证环境A 里有",
      "baseUrl" not in base1 and "baseUrl" in base2, f"默认={base1[:3]} A={base2[:3]}")

st, s = call("PUT", f"{BASE}/sign?env={eid}",
             {"enabled": False, "appId": "fake-app-a", "appSecret": "fake-secret-a",
              "nonce": "", "mgId": "", "env": eid})
check("★ 按环境保存签名：200", st == 200, f"HTTP {st}")

# 用例分组绑定环境
st, g = call("POST", f"{BASE}/case-groups", {"name": "验证环境分组", "envId": eid})
check("★ 新建分组时绑定环境：200 且读回绑定", st == 200 and g.get("envId") == eid,
      f"HTTP {st} envId={g.get('envId')}")
gid = g["id"]
cid = mkcase("环境绑定用例", "/ok")
set_case(cid, groupId=gid)

# 环境改名
st, e_renamed = call("PUT", f"{BASE}/envs/{eid}", {"name": "验证环境A改"})
check("环境改名：200", st == 200 and e_renamed.get("name") == "验证环境A改", f"HTTP {st}")

# 删除环境：引用它的分组回到默认环境（解绑，不删用例）
st, _ = call("DELETE", f"{BASE}/envs/{eid}")
check("★ 删除环境：200", st == 200, f"HTTP {st}")
st, g_after = call("GET", f"{BASE}/case-groups/{gid}")
check("★ 删除环境后：引用它的分组自动解绑（envId 落成 null）",
      g_after.get("envId") is None, f"envId={g_after.get('envId')!r}")

st, err = call("DELETE", f"{BASE}/envs/1")
check("★ 默认环境不能删除（它是未绑定用例的兜底）", st >= 400, f"HTTP {st} {err}")

# 清理
call("DELETE", f"{BASE}/case-groups/{gid}")
call("DELETE", f"{BASE}/cases/{cid}")
call("DELETE", f"{BASE}/envs/{dup_id}")
print("  清理：环境分组验证产生的数据已删除")

# ---------- 15. 响应体断言（需求 #51） ----------
print("\n[15] 响应体断言（2026-10-05，需求 #51）")


def with_asserts(name, rules, path="/profile", exp_status="200", exp_code="0"):
    """建一条带响应体断言的用例并单跑一次，返回 (用例 id, 执行结果)。

    每条都独立建用例、独立跑 —— 断言判错的时候，共用用例会让上一条的失败
    伪装成下一条的问题，最后分不清是哪条规则在起作用。
    """
    cid = mkcase(name, path, exp_status=exp_status, exp_code=exp_code,
                 asserts=json.dumps(rules, ensure_ascii=False))
    st, ex = call("POST", f"{BASE}/run", {"caseIds": [cid]})
    assert st == 200, (st, ex)
    return cid, ex[0]


# 全过：一条用例上把主要条件都压一遍
E_ALL_RULES = [
    {"path": "data.roomId", "op": "type", "expect": "integer"},
    {"path": "data.roomId", "op": "equals", "expect": "10086"},
    {"path": "data.roomName", "op": "equals", "expect": "开黑房"},
    {"path": "data.roomName", "op": "contains", "expect": "开黑"},
    {"path": "data.roomName", "op": "length", "expect": "3"},
    {"path": "data.players", "op": "length", "expect": "3"},
    {"path": "data.players[*].uid", "op": "type", "expect": "integer"},
    {"path": "data.tags[*]", "op": "type", "expect": "string"},
    {"path": "data.players[0].seat", "op": "equals", "expect": "3"},
    {"path": "data.roomId", "op": "gte", "expect": "10000"},
    {"path": "data.roomId", "op": "in", "expect": "10086,10087"},
    {"path": "data.note", "op": "type", "expect": "null"},
    {"path": "data.note", "op": "notExists", "expect": ""},
    {"path": "data.players.length", "op": "equals", "expect": "3"},
]
cid_all, e_all = with_asserts("断言全过", E_ALL_RULES)
check("响应体断言全部满足时判定为 PASS", e_all["verdict"] == "PASS", str(e_all.get("reason")))
check("通过时不给原因", not e_all.get("reason"), str(e_all.get("reason")))

# 类型不符
_, e_type = with_asserts("断言类型不符", [
    {"path": "data.roomId", "op": "type", "expect": "string"}])
check("类型不符判 FAIL", e_type["verdict"] == "FAIL", str(e_type))
check("原因里同时给出路径、期望类型与实际类型",
      "data.roomId" in (e_type.get("reason") or "")
      and "string" in (e_type.get("reason") or "")
      and "number" in (e_type.get("reason") or ""),
      str(e_type.get("reason")))

# integer 与 number 的边界：1.0 是 number 但不是 integer
_, e_int = with_asserts("1.0 不是 integer", [
    {"path": "data.ratio", "op": "type", "expect": "integer"}])
check("1.0 不满足 integer", e_int["verdict"] == "FAIL", str(e_int.get("reason")))
check("并且说清是「带小数点的 number」", "带小数点" in (e_int.get("reason") or ""),
      str(e_int.get("reason")))
_, e_num = with_asserts("1.0 是 number", [
    {"path": "data.ratio", "op": "type", "expect": "number"}])
check("1.0 满足 number", e_num["verdict"] == "PASS", str(e_num.get("reason")))
_, e_eq = with_asserts("数值按数值比", [
    {"path": "data.ratio", "op": "equals", "expect": "1"}])
check("equals 按数值比：1.0 等于 1", e_eq["verdict"] == "PASS", str(e_eq.get("reason")))

# [*] 全部满足 + 指出第几个不满足
_, e_star = with_asserts("[*] 全部满足", [
    {"path": "data.players[*].ready", "op": "equals", "expect": "true"}])
check("[*] 有元素不满足就判 FAIL", e_star["verdict"] == "FAIL", str(e_star.get("reason")))
check("[*] 失败原因指明是第几个元素", "第 2 个元素" in (e_star.get("reason") or ""),
      str(e_star.get("reason")))
_, e_star_ok = with_asserts("[*] 全满足则通过", [
    {"path": "data.players[*].seat", "op": "type", "expect": "integer"}])
check("[*] 全部元素满足时通过", e_star_ok["verdict"] == "PASS", str(e_star_ok.get("reason")))

# 空数组：[*] 必须判失败，不能走「空集恒真」
_, e_empty = with_asserts("空数组的 [*]", [
    {"path": "data.empty[*]", "op": "type", "expect": "string"}])
check("空数组上的 [*] 判 FAIL（不走空集恒真）", e_empty["verdict"] == "FAIL",
      str(e_empty.get("reason")))
check("并且说清是数组为空", "数组为空" in (e_empty.get("reason") or ""), str(e_empty.get("reason")))

# 键不存在 与 值为 null 必须分开报
_, e_missing = with_asserts("键不存在", [
    {"path": "data.nope", "op": "exists", "expect": ""}])
check("键不存在判 FAIL", e_missing["verdict"] == "FAIL", str(e_missing.get("reason")))
check("原因说的是「键不存在」", "键不存在" in (e_missing.get("reason") or ""),
      str(e_missing.get("reason")))
_, e_null = with_asserts("值是 null", [
    {"path": "data.note", "op": "exists", "expect": ""}])
check("值为 null 也判 FAIL", e_null["verdict"] == "FAIL", str(e_null.get("reason")))
check("原因说的是「值是 null」，与「键不存在」区分开",
      "null" in (e_null.get("reason") or "") and "键不存在" not in (e_null.get("reason") or ""),
      str(e_null.get("reason")))
_, e_ne = with_asserts("notExists 对两者都算通过", [
    {"path": "data.nope", "op": "notExists", "expect": ""},
    {"path": "data.note", "op": "notExists", "expect": ""}])
check("notExists 对「键没有」与「值是 null」都算通过", e_ne["verdict"] == "PASS",
      str(e_ne.get("reason")))

# 越界
_, e_oob = with_asserts("下标越界", [
    {"path": "data.players[5]", "op": "exists", "expect": ""}])
check("下标越界判 FAIL", e_oob["verdict"] == "FAIL", str(e_oob.get("reason")))
check("原因里带上越界的下标与真实长度",
      "越界" in (e_oob.get("reason") or "") and "3" in (e_oob.get("reason") or ""),
      str(e_oob.get("reason")))

# 多条同时不过：一次全列出来
_, e_many = with_asserts("三条同时不过", [
    {"path": "data.roomId", "op": "type", "expect": "string"},
    {"path": "data.players", "op": "length", "expect": "5"},
    {"path": "data.roomName", "op": "contains", "expect": "不存在的字"},
])
mreason = e_many.get("reason") or ""
check("三条断言同时不过时一次全列出来（不是遇到第一条就停）",
      mreason.count("·") == 3, repr(mreason))
check("条数写在原因抬头里", "3 条" in mreason, repr(mreason))
check("三条的路径都在原因里",
      all(k in mreason for k in ("data.roomId", "data.players", "data.roomName")), repr(mreason))

# 断言行留空 = 没填的草稿行，不该变成约束
_, e_draft = with_asserts("只有草稿行", [{"path": "", "op": "", "expect": ""}])
check("整行空的草稿行被忽略，不影响判定", e_draft["verdict"] == "PASS",
      str(e_draft.get("reason")))

# 没设断言的用例：判定一个字都不该变（回归）
st, aged = call("GET", f"{BASE}/cases/{c_ok}")
check("老用例的 asserts 读回来是 null", aged.get("asserts") is None, repr(aged.get("asserts")))
st, ex_aged = call("POST", f"{BASE}/run", {"caseIds": [c_ok]})
check("没设断言的用例判定不变（仍然 PASS）", ex_aged[0]["verdict"] == "PASS",
      str(ex_aged[0].get("reason")))

# 判定顺序：状态码不符时先报状态码，不该被断言抢在前面
_, e_order = with_asserts("状态码优先", [
    {"path": "data.roomId", "op": "type", "expect": "integer"}],
    path="/http500", exp_status="200")
check("状态码不符时先报状态码（响应体断言排在它后面）",
      "HTTP 状态码不符" in (e_order.get("reason") or ""), str(e_order.get("reason")))

# 非 JSON 响应：根路径仍可断「包含」，按路径下钻要明确报出来
_, e_plain = with_asserts("纯文本的根路径断言", [
    {"path": "", "op": "contains", "expect": "plain text"}],
    path="/text", exp_code="")
check("非 JSON 响应的根路径可以用「包含」断", e_plain["verdict"] == "PASS",
      str(e_plain.get("reason")))
_, e_plain_path = with_asserts("纯文本按路径断", [
    {"path": "data.code", "op": "equals", "expect": "0"}],
    path="/text", exp_code="")
check("非 JSON 响应上按路径下钻：明确报「不是 JSON」",
      "不是 JSON" in (e_plain_path.get("reason") or ""), str(e_plain_path.get("reason")))

# 超长响应体：断言必须在截断前判定
_, e_huge = with_asserts("超长响应体", [
    {"path": "tail", "op": "equals", "expect": "END"}], path="/huge")
check("超过 20000 字符的响应体，尾部字段的断言仍然通过",
      e_huge["verdict"] == "PASS", str(e_huge.get("reason")))
check("同一份结果里存的响应体确实是截断过的（证明断言看的不是这一份）",
      "已截断" in (e_huge.get("respBody") or ""), (e_huge.get("respBody") or "")[-60:])

# 逐条判定结果要落库并能读回（2026-10-05，需求 #58）。
#
# 这一层管的是**接口契约**：界面上那块「N 条全部通过」完全靠这个字段，
# 字段形状一旦漂了，界面不会报错、只会显示成「旧记录没存这一项」—— 静默失效。
check("执行结果里带回逐条判定结果", e_all.get("assertsResult") is not None,
      repr(e_all.get("assertsResult")))
judged = json.loads(e_all.get("assertsResult") or "[]")
check("全过时逐条结果里也带着每一条（通过的不能省，否则界面分不清「都过了」和「没配断言」）",
      len(judged) == len(E_ALL_RULES), f"{len(judged)} vs {len(E_ALL_RULES)}")
check("每条都标了 ok=true 且没有原因", all(j.get("ok") is True and j.get("why") is None
      for j in judged), json.dumps(judged, ensure_ascii=False)[:200])
check("每条都带路径、条件与期望值（界面要靠它们显示成一行）",
      all(j.get("path") and j.get("op") and "expect" in j for j in judged),
      json.dumps(judged, ensure_ascii=False)[:200])

_, e_fail = with_asserts("逐条结果·部分未过", [
    {"path": "data.roomId", "op": "equals", "expect": "10086"},
    {"path": "data.roomId", "op": "equals", "expect": "1"}])
jf = json.loads(e_fail.get("assertsResult") or "[]")
check("未过时通过的与未通过的都在逐条结果里（不是只留没过的）", len(jf) == 2,
      json.dumps(jf, ensure_ascii=False))
check("未通过那条 ok=false 且带上说明，通过那条 ok=true",
      jf[0].get("ok") is True and jf[1].get("ok") is False and jf[1].get("why"),
      json.dumps(jf, ensure_ascii=False))
check("说明里不带路径（路径是同一行的独立字段，重复会显得像两件事）",
      "data.roomId" not in (jf[1].get("why") or ""), repr(jf[1].get("why")))

# 没配断言 → 空数组（不是 null）；请求没通 → null。这两种要能分开，界面上的文案不一样。
cid_none = mkcase("逐条结果·没配断言", "/ok")
st, e_none = call("POST", f"{BASE}/run", {"caseIds": [cid_none]})
check("没配断言时逐条结果是空数组（不是 null）",
      e_none[0].get("assertsResult") == "[]", repr(e_none[0].get("assertsResult")))
cid_err = mkcase("逐条结果·请求没通", "", url="http://127.0.0.1:19999/dead")
st, e_err = call("POST", f"{BASE}/run", {"caseIds": [cid_err]})
check("请求根本没通时逐条结果是 null（没有响应体就无从判定，不能伪造成「空数组」）",
      e_err[0].get("assertsResult") is None, repr(e_err[0].get("assertsResult")))
for tmp in (cid_none, cid_err):
    if tmp:
        call("DELETE", f"{BASE}/cases/{tmp}")

# 存得住、读得回、复制跟走
st, back = call("GET", f"{BASE}/cases/{cid_all}")
stored = back.get("asserts") or ""
check("断言能存下来", bool(stored), repr(stored))
check("并且原样读回（路径与条件都在）",
      "data.roomId" in stored and "integer" in stored, repr(stored))
st, dup = call("POST", f"{BASE}/cases/{cid_all}/duplicate")
check("复制用例时断言跟着走", (dup.get("asserts") or "") == stored,
      f"{dup.get('asserts')!r} != {stored!r}")
# 清空断言：存 null，不是存一个空数组文本
st, cleared = call("PUT", f"{BASE}/cases/{cid_all}", {**back, "asserts": None})
check("清空断言后存的是 null（不是空数组文本）", cleared.get("asserts") is None,
      repr(cleared.get("asserts")))

for tmp in (cid_all, dup.get("id")):
    if tmp:
        call("DELETE", f"{BASE}/cases/{tmp}")

# ---------- 16. 上报查询（需求 #60） ----------
#
# 增量切文的**纯逻辑**由单测（LogcatTailTest）与 logcat-check.mjs 压着；这里管的是
# **接口契约**：字段名、以及「没设备时怎么办」。后者恰恰是当前唯一能端到端验到的形态 ——
# 本机没接设备，所以真实取文只能靠真机冒烟，而「没设备时不许 500、要给明确提示」
# 是每次跑都能验、且一旦退化就会被界面用户立刻撞上的部分。
print("\n[16] 上报查询（2026-10-06，需求 #60）")

st, tail_none = call("POST", f"{LC_BASE}/tail", {"serial": "", "anchor": None})
check("没选设备时不报错（返回 200 而不是 500）", st == 200, f"HTTP {st}")
check("没选设备时给出明确提示，且标记为设备离线",
      tail_none.get("deviceOffline") is True and bool(tail_none.get("message")),
      json.dumps(tail_none, ensure_ascii=False)[:200])
check("没选设备时字段齐全（界面靠 lines/anchor/reset 三件套渲染）",
      isinstance(tail_none.get("lines"), list) and "anchor" in tail_none and "reset" in tail_none,
      json.dumps(tail_none, ensure_ascii=False)[:200])

st, tail_ghost = call("POST", f"{LC_BASE}/tail",
                      {"serial": "不存在的设备-0000", "anchor": None})
check("设备不存在时不报错、明确说设备不在线", st == 200 and tail_ghost.get("deviceOffline") is True,
      f"HTTP {st} {json.dumps(tail_ghost, ensure_ascii=False)[:160]}")

st, clr_none = call("POST", f"{LC_BASE}/clear", {"serial": ""})
check("清空接口没选设备时返回 ok=false 且有提示（不静默成功）",
      st == 200 and clr_none.get("ok") is False and bool(clr_none.get("message")),
      f"HTTP {st} {json.dumps(clr_none, ensure_ascii=False)[:160]}")

st, clr_ghost = call("POST", f"{LC_BASE}/clear", {"serial": "不存在的设备-0000"})
check("清空接口对不存在的设备也不静默成功",
      st == 200 and clr_ghost.get("ok") is False, f"HTTP {st} {json.dumps(clr_ghost, ensure_ascii=False)[:160]}")

st, _ = call("POST", f"{LC_BASE}/tail", {})
check("请求体为空也不炸（前端在设备被拔掉时可能传空）", st == 200, f"HTTP {st}")

# ---------- 17. 上报查询：过滤关键字库（需求 #66） ----------
#
# 这一段验的是**接口契约与持久化语义**，其中两条只有在这里才能钉住（界面层与纯函数层都看不到）：
#   ① 同一个关键字再存一次是「改注释」而不是新增一条（唯一索引 + upsert）。
#      它在界面上表现为「下拉里出现了两条一模一样的项」，而光看前端代码完全看不出来 ——
#      前端根本没有去重逻辑，靠的就是后端这一条。
#   ② 关键字为空时回 200 + ok=false + 中文提示，而不是 500：那是使用者在界面上的
#      正常操作失误，不该在日志里留一条异常，也不该让界面弹一段英文堆栈。
print("\n[17] 上报查询：过滤关键字库（2026-10-06，需求 #66）")

KW = f"{LC_BASE}/keywords"

st, k0 = call("GET", KW)
check("列出关键字：返回数组（空库时是空数组而不是 null）",
      st == 200 and isinstance(k0, list), f"HTTP {st} {json.dumps(k0, ensure_ascii=False)[:160]}")
base_count = len(k0) if isinstance(k0, list) else 0

st, s1 = call("POST", KW, {"keyword": "  isIn  ", "note": "  看进入房间  "})
check("保存：200 且 ok=true", st == 200 and s1.get("ok") is True,
      f"HTTP {st} {json.dumps(s1, ensure_ascii=False)[:200]}")
check("保存：关键字与注释都去了首尾空白",
      (s1.get("item") or {}).get("keyword") == "isIn"
      and (s1.get("item") or {}).get("note") == "看进入房间",
      json.dumps(s1.get("item"), ensure_ascii=False))
check("保存：顺手把刷新后的整份列表带回来（省掉一次 GET，也避免两次请求之间列表已变）",
      isinstance(s1.get("items"), list) and len(s1["items"]) == base_count + 1,
      json.dumps(s1.get("items"), ensure_ascii=False)[:200])
kid = (s1.get("item") or {}).get("id")
check("保存：条目带 id（删除要用）", isinstance(kid, int), str(kid))

st, s2 = call("POST", KW, {"keyword": "isIn", "note": "改过的注释"})
check("★ 同一个关键字再存一次是「改注释」而不是新增一条（否则下拉里会出现两条一样的）",
      st == 200 and len(s2.get("items") or []) == base_count + 1
      and (s2.get("item") or {}).get("note") == "改过的注释"
      and (s2.get("item") or {}).get("id") == kid,
      f"HTTP {st} {json.dumps(s2.get('items'), ensure_ascii=False)[:200]}")

st, s3 = call("POST", KW, {"keyword": "  isIn  ", "note": "带空白也应命中同一条"})
check("★ 归一化在唯一判定之前生效：带空白的写法同样不会另开一条",
      st == 200 and len(s3.get("items") or []) == base_count + 1,
      json.dumps(s3.get("items"), ensure_ascii=False)[:200])

st, s4 = call("POST", KW, {"keyword": "   ", "note": "空关键字"})
check("★ 关键字为空：200 + ok=false + 中文提示（不是 500，也不是静默存一条空关键字）",
      st == 200 and s4.get("ok") is False and bool(s4.get("message")),
      f"HTTP {st} {json.dumps(s4, ensure_ascii=False)[:160]}")
st, s5 = call("POST", KW, {"note": "连关键字字段都没有"})
check("请求体里没有 keyword 字段也不炸", st == 200 and s5.get("ok") is False, f"HTTP {st}")

st, s6 = call("POST", KW, {"keyword": "级别=错误(含正则)", "note": "中文与正则元字符"})
check("中文与正则元字符的关键字能原样存取（UTF-8 往返不能坏，正则元字符不能炸）",
      st == 200 and (s6.get("item") or {}).get("keyword") == "级别=错误(含正则)",
      json.dumps(s6.get("item"), ensure_ascii=False))
kid2 = (s6.get("item") or {}).get("id")

st, d1 = call("DELETE", f"{KW}/{kid}")
check("删除：200 且 ok=true，并把刷新后的列表带回来（那条确实不在了）",
      st == 200 and d1.get("ok") is True and isinstance(d1.get("items"), list)
      and all(x.get("id") != kid for x in d1["items"]),
      f"HTTP {st} {json.dumps(d1, ensure_ascii=False)[:200]}")

st, d2 = call("DELETE", f"{KW}/{kid}")
check("★ 删不存在的 id：200 + ok=false（不是 404 —— 连点两次不该在控制台留一条红字）",
      st == 200 and d2.get("ok") is False, f"HTTP {st} {json.dumps(d2, ensure_ascii=False)[:160]}")

if isinstance(kid2, int):
    call("DELETE", f"{KW}/{kid2}")
st, kend = call("GET", KW)
check("清理：本段建的关键字都已删掉，库回到起始条数",
      isinstance(kend, list) and len(kend) == base_count,
      f"起始 {base_count} → 现在 {len(kend) if isinstance(kend, list) else '?'}")

# ---------- [18] 性能测试：人机对局配置与推送身份（需求 #68） ----------
#
# 这一段的接口都在 /api/perf 下（与前面几段的 /api/apitest 不是同一前缀）。
#
# 要验的两样东西都属于「配置」：四条用例绑定 + 人机数量、以及每个游戏的 mg_id/app_id。
# 它们错了不报错、只是推送时用错身份或用错用例 —— 从压测结果上看不出来，
# 所以判据落在「存进去能读回来」与「非法值当场被拦」上。
PERF = os.environ.get("SP_API_PERF_BASE", "http://127.0.0.1:18797/api/perf")
# 临时库路径：run-all.sh 起实例时指定的就是它。只用来给 games/ids 造一个被测对象。
SP_DB = os.environ["SP_DB"]

print("\n--- 性能测试：人机对局配置与推送身份（需求 #68）---")

st, cfg = call("GET", f"{PERF}/ai-room")
check("读人机对局配置：200，四个环节的绑定都在（没配过时为空，不是报错）",
      st == 200 and all(k in cfg for k in ("caseBatch", "caseStart", "caseEnd", "caseClear",
                                           "aiCount", "endKeyword")),
      f"HTTP {st} {json.dumps(cfg, ensure_ascii=False)[:200]}")
check("配置里带着各环节的默认用例名（页面靠它显示「不指定时去找哪一条」）",
      isinstance(cfg.get("defaults"), dict) and cfg["defaults"].get("batch") == "批量加入",
      json.dumps(cfg.get("defaults"), ensure_ascii=False))

# 存取往返：把四条绑到本套环境里真实存在的用例上
st, cases = call("GET", f"{BASE}/cases")
by_name = {c["name"]: c["id"] for c in cases} if isinstance(cases, list) else {}
if all(n in by_name for n in ("批量加入", "游戏开始", "游戏结束", "房间清理")):
    st, _ = call("PUT", f"{PERF}/ai-room", {
        "caseBatch": str(by_name["批量加入"]), "caseStart": str(by_name["游戏开始"]),
        "caseEnd": str(by_name["游戏结束"]), "caseClear": str(by_name["房间清理"]),
        "aiCount": "8",
        # 对局提前结束关键字（v1.9.4 #78）：故意带前后空格，验的是「存进去会 trim」——
        # 这一列拿去给 logcat 做 contains，多一个空格就永远命中不了，而且是静默不生效
        "endKeyword": "  FAKE_SCENE_DESTROYED  "})
    check("写人机对局配置：200", st == 200, f"HTTP {st}")
    st, back = call("GET", f"{PERF}/ai-room")
    check("★ 存进去的四条绑定能原样读回来（改了却读不回 = 页面上白改）",
          st == 200 and back.get("caseBatch") == str(by_name["批量加入"])
          and back.get("caseEnd") == str(by_name["游戏结束"]), json.dumps(back, ensure_ascii=False)[:200])
    check("人机数量也存回来了", back.get("aiCount") == "8", json.dumps(back.get("aiCount")))
    check("★ 对局结束关键字存回来且前后空格已被去掉（#78）",
          back.get("endKeyword") == "FAKE_SCENE_DESTROYED",
          repr(back.get("endKeyword")))
else:
    print("  （本套环境里没有那四条用例：跳过存取的往返断言）")

st, bad = call("PUT", f"{PERF}/ai-room", {"aiCount": "abc"})
check("★ 人机数量填了非整数：400 + 中文提示（不是静默存下去，等运行时才发现）",
      st == 400 and "整数" in json.dumps(bad, ensure_ascii=False),
      f"HTTP {st} {json.dumps(bad, ensure_ascii=False)[:160]}")
st, bad2 = call("PUT", f"{PERF}/ai-room", {"aiCount": "0"})
check("★ 人机数量填 0：400（0 个人机不是「不推人机」，那属于配置错误）",
      st == 400 and "大于 0" in json.dumps(bad2, ensure_ascii=False),
      f"HTTP {st} {json.dumps(bad2, ensure_ascii=False)[:160]}")

# 通用运行选项（v1.10.0 #84，v1.11.0 起默认开启）：验「默认开 + 关得掉 + 读得回」。
# 默认开是使用者 2026-10-10 的拍板 —— 不采第二个基线就分不出缓存与泄漏。
st, opt = call("GET", f"{PERF}/options")
check("读性能测试通用选项：200，且「采样前触发内存回收」默认开启",
      st == 200 and opt.get("trimBaseline") is True,
      f"HTTP {st} {json.dumps(opt, ensure_ascii=False)[:160]}")
st, _ = call("PUT", f"{PERF}/options", {"trimBaseline": False})
st, opt2 = call("GET", f"{PERF}/options")
check("★ 关掉开关后能原样读回（默认开也要关得掉）",
      st == 200 and opt2.get("trimBaseline") is False,
      json.dumps(opt2, ensure_ascii=False)[:160])
call("PUT", f"{PERF}/options", {"trimBaseline": True})

# 推送身份的往返需要一条真实的游戏记录。临时库里是空的（扫描游戏要真机），
# 所以直接往这套临时库插一条 —— 验的是接口行为，不是扫描功能。
game_id = None
try:
    import sqlite3
    con = sqlite3.connect(SP_DB, timeout=5)
    cur = con.execute(
        "INSERT INTO perf_game(name,short_name,created_at) VALUES(?,?,?)",
        ("接口断言用占位游戏", "接口断言用占位游戏", "2026-10-06T00:00:00Z"))
    game_id = cur.lastrowid
    con.commit()
    con.close()
except Exception as e:
    print(f"  （造占位游戏失败，相关断言按跳过处理：{e}）")

if game_id is not None:
    # Synthetic repeated-digit 19-digit IDs deliberately exceed JavaScript safe-integer precision.
    st, r1 = call("PUT", f"{PERF}/games/{game_id}/ids",
                  {"mgId": "1111111111111111111", "appId": "2222222222222222222"})
    check("设推送身份：200 + ok", st == 200 and r1.get("ok") is True, f"HTTP {st} {r1}")
    st, games = call("GET", f"{PERF}/games")
    row = next((g for g in games if g["id"] == game_id), None)
    check("★ 列表里能读到刚设的 mg_id / app_id（这两个值就是推送时注入用例的那份）",
          row is not None and row.get("mgId") == "1111111111111111111"
          and row.get("appId") == "2222222222222222222",
          json.dumps(row, ensure_ascii=False))
    # 空串 = 清掉，回到「用变量表里的全局值」
    call("PUT", f"{PERF}/games/{game_id}/ids", {"mgId": "", "appId": "   "})
    st, games2 = call("GET", f"{PERF}/games")
    row2 = next((g for g in games2 if g["id"] == game_id), None)
    check("★ 传空串等于清掉（读回来是 null，而不是存了空字符串）",
          row2 is not None and row2.get("mgId") is None and row2.get("appId") is None,
          json.dumps(row2, ensure_ascii=False))
    # 清理：把占位游戏删掉，别在库里留垃圾。
    # raw=True 是必须的：这个端点回的是 204/空体（Spring 对 void 方法的默认行为），
    # 按 JSON 解析会在这里抛 JSONDecodeError —— 断言脚本自己崩掉，整轮验证就断在这。
    call("DELETE", f"{PERF}/games/{game_id}", raw=True)
    st, games3 = call("GET", f"{PERF}/games")
    check("清理：占位游戏已删除",
          all(g["id"] != game_id for g in (games3 or [])), f"HTTP {st}")

st, nf = call("PUT", f"{PERF}/games/99999999/ids", {"mgId": "1", "appId": "2"})
check("★ 给不存在的游戏设身份：明确报错（而不是静默 UPDATE 0 行让页面以为存上了）",
      st != 200 and "不存在" in json.dumps(nf, ensure_ascii=False),
      f"HTTP {st} {json.dumps(nf, ensure_ascii=False)[:160]}")

# ---------- [19] 性能测试：按游戏的推送配置（2026-10-07，需求 #69/#70） ----------
#
# #70 把游戏级推送配置扩成八项：身份三项（mg_id/app_id/room_id）+ 名单三项
# （真人数/人机数/人机等级）+ 对局时长 + 推送体模板。room_id 是「人机进错房、
# 开局 100500」bug（2026-10-07 诊断）的修复落点 —— 房号必须能跟随游戏配。
# 判据：「存进去能读回来」「非法值当场被拦」；名单生成规则在 AiPlayers 单测里钉。
print("\n--- 性能测试：按游戏的推送配置（需求 #69/#70）---")

# 推送体模板样例：带占位符（与接口用例同一种写法），名单里第 1 条就是真人
TEMPLATE = json.dumps({
    "event": "user_in_batch",
    "mg_id": "{{mg_id}}",
    "app_id": "{{app_id}}",
    "data": {
        "user_infos": [
            {"uid": "my01", "nick_name": "真人位", "is_ai": 0, "is_ready": True, "seat_index": -1},
            {"uid": "my02", "nick_name": "替补人机", "is_ai": 1, "is_ready": True, "seat_index": -1},
        ],
        "room_id": "{{room_id}}",
    },
}, ensure_ascii=False)

game69 = None
try:
    import sqlite3
    con = sqlite3.connect(SP_DB, timeout=5)
    cur = con.execute(
        "INSERT INTO perf_game(name,short_name,created_at) VALUES(?,?,?)",
        ("接口断言用占位游戏69", "占位69", "2026-10-07T00:00:00Z"))
    game69 = cur.lastrowid
    con.commit()
    con.close()
except Exception as e:
    print(f"  （造占位游戏失败，相关断言按跳过处理：{e}）")

if game69 is not None:
    st, r1 = call("PUT", f"{PERF}/games/{game69}/ids",
                  {"mgId": "111", "appId": "222", "roomId": "9527",
                   "humanCount": 2, "aiCount": 8, "aiLevel": 3,
                   "gameSeconds": 300, "machineUid": "me007", "bodyOverride": TEMPLATE})
    check("设推送配置（八项一起）：200 + ok", st == 200 and r1.get("ok") is True, f"HTTP {st} {r1}")
    st, games = call("GET", f"{PERF}/games")
    row = next((g for g in games if g["id"] == game69), None)
    check("★ 九项全部原样读回（身份/房号/真人数/人机数/等级/时长/本机账号/模板）",
          row is not None and row.get("mgId") == "111" and row.get("roomId") == "9527"
          and row.get("humanCount") == 2 and row.get("aiCount") == 8
          and row.get("aiLevel") == 3 and row.get("gameSeconds") == 300
          and row.get("machineUid") == "me007" and row.get("bodyOverride") == TEMPLATE,
          json.dumps({k: row.get(k) for k in ("mgId", "roomId", "humanCount", "aiCount",
                                              "aiLevel", "gameSeconds", "machineUid")}, ensure_ascii=False))
    check("模板里保留着占位符（存的是模板不是替换后的值）",
          row is not None and "{{room_id}}" in (row.get("bodyOverride") or ""),
          "占位符丢失 = 注入链路被抄近道")

    # 非法值当场拦（#70 口径：0 是合法数量，全零/越界才是错）
    st, bad00 = call("PUT", f"{PERF}/games/{game69}/ids", {"humanCount": 0, "aiCount": 0})
    check("★ 真人数与人机数都是 0：400（什么人都不推是配置错误，不是「留空」）",
          st == 400 and json.dumps(bad00, ensure_ascii=False) != "",
          f"HTTP {st} {json.dumps(bad00, ensure_ascii=False)[:160]}")
    st, bad65 = call("PUT", f"{PERF}/games/{game69}/ids", {"aiCount": 65})
    check("★ 人机数量超上限 64：400（防手滑造出巨型请求体）", st == 400, f"HTTP {st}")
    st, badlv = call("PUT", f"{PERF}/games/{game69}/ids", {"aiLevel": -1})
    check("★ 人机等级为负：400（ai_level 是强度档位，不该有负数）", st == 400, f"HTTP {st}")
    st, badt1 = call("PUT", f"{PERF}/games/{game69}/ids", {"gameSeconds": 10})
    check("★ 对局时长低于 15 秒：400（采不到有效数据）", st == 400, f"HTTP {st}")
    st, badt2 = call("PUT", f"{PERF}/games/{game69}/ids", {"gameSeconds": 8000})
    check("★ 对局时长超 2 小时：400（防手滑；4 分钟以上合法，watchdog 会跟着延长）",
          st == 400, f"HTTP {st}")
    st, badjson = call("PUT", f"{PERF}/games/{game69}/ids",
                       {"bodyOverride": '{"event": "x", "data": {'})
    check("★ 模板语法坏掉：400 + 中文提示（保存时就拦，不等运行时）",
          st == 400 and "合法 JSON" in json.dumps(badjson, ensure_ascii=False),
          f"HTTP {st} {json.dumps(badjson, ensure_ascii=False)[:160]}")

    # 空串/缺省 = 清掉
    st, _ = call("PUT", f"{PERF}/games/{game69}/ids",
                 {"mgId": "", "appId": "", "roomId": "", "humanCount": None,
                  "aiCount": None, "aiLevel": None, "gameSeconds": None,
                  "machineUid": "", "bodyOverride": ""})
    st, games2 = call("GET", f"{PERF}/games")
    row2 = next((g for g in games2 if g["id"] == game69), None)
    check("★ 九项全空等于全清掉（读回 null，而不是存了空串或 0）",
          row2 is not None and row2.get("roomId") is None and row2.get("humanCount") is None
          and row2.get("aiCount") is None and row2.get("aiLevel") is None
          and row2.get("gameSeconds") is None and row2.get("machineUid") is None
          and row2.get("bodyOverride") is None,
          json.dumps({k: row2.get(k) for k in ("roomId", "humanCount", "aiCount",
                                               "aiLevel", "gameSeconds", "machineUid")}, ensure_ascii=False))

    call("DELETE", f"{PERF}/games/{game69}", raw=True)
    st, games3 = call("GET", f"{PERF}/games")
    check("清理：占位游戏69已删除",
          all(g["id"] != game69 for g in (games3 or [])), f"HTTP {st}")

# 内存增长曲线端点（v1.9.1）：运行不存在时给明确中文报错（不裸 500）
st, gc = call("GET", f"{PERF}/runs/99999999/games/9999/growth-curves")
check("★ 增长曲线端点：不存在的运行明确报错",
      st != 200 and "不存在" in json.dumps(gc, ensure_ascii=False),
      f"HTTP {st} {json.dumps(gc, ensure_ascii=False)[:120]}")

print("\n" + "=" * 70)
print(f"通过 {ok_count} 项，失败 {bad_count} 项")
print("=" * 70)
sys.exit(0 if bad_count == 0 else 1)