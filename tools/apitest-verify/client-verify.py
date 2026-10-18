#!/usr/bin/env python3
"""Wire-level client/scenario regression; only an isolated loopback instance.

Exercise and restart phases share a JSON manifest. No formal database is read.
The independent mock is the request oracle; platform snapshots are verified
against received bytes rather than against another platform-generated field.
"""
import argparse
import base64
import json
import os
import re
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

BASE = os.environ.get("SP_API_BASE", "http://127.0.0.1:18795/api/apitest").rstrip("/")
MOCK = os.environ.get("SP_API_MOCK", "http://127.0.0.1:18889").rstrip("/")
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
LONG_ID = "12345678901234567890123456789012345678"
SPECIAL = '中文 "quoted" \\slash\nnext line\tend'
SIGN_KEY = "dummy-client-regression-key"
CHECKS = []
SECTIONS = []
SUFFIX = uuid.uuid4().hex[:8]


def loopback(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
        raise RuntimeError("专项验证只接受 http://127.0.0.1 地址")


def call(method, url, payload=None, timeout=30):
    loopback(url)
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    try:
        response = HTTP.open(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode("utf-8", "replace")
        try:
            value = json.loads(raw) if raw else None
        except ValueError:
            value = {"raw": raw}
        return response.status, value


def api(method, path, payload=None):
    return call(method, BASE + path, payload)


def must(method, path, payload=None):
    status, result = api(method, path, payload)
    if not 200 <= status < 300:
        raise AssertionError(f"{method} {path}: HTTP {status}: {result!r}")
    return result


def control(path, payload=None):
    status, value = call("GET" if payload is None else "POST", MOCK + "/_control/" + path, payload)
    if status != 200:
        raise AssertionError(f"mock control {path}: HTTP {status}: {value}")
    return value


def reset():
    control("reset", {})


def journal():
    return control("journal")


def check(name, condition, detail=None):
    passed = bool(condition)
    CHECKS.append({"name": name, "passed": passed})
    print(("PASS " if passed else "FAIL ") + name, flush=True)
    if not passed and detail is not None:
        print("     " + repr(detail)[:1000], flush=True)
    return passed


def section(name, function):
    print("\n[" + name + "]", flush=True)
    before = len(CHECKS)
    try:
        function()
    except Exception as error:
        check(name + " 完整执行", False, f"{type(error).__name__}: {error}")
        traceback.print_exc(limit=3)
    SECTIONS.append({"name": name, "checks": len(CHECKS) - before,
                     "passed": all(c["passed"] for c in CHECKS[before:])})


def options(**changes):
    return dict({"bodyType": "JSON", "params": [], "auth": {"type": "NONE"},
                 "extractors": []}, **changes)


def spec(name, path="/capture", body=None, **changes):
    return dict({"name": name + "-" + SUFFIX, "method": "POST", "url": MOCK + path,
                 "headers": "{}", "body": body, "note": "隔离 HTTP 专项夹具",
                 "expectStatus": "200", "expectCode": "", "timeoutMs": 8000,
                 "insecure": False, "enabled": True, "asserts": None,
                 "options": options()}, **changes)


def create_case(draft):
    return must("POST", "/cases", draft)


def update_case(case, **changes):
    return must("PUT", f'/cases/{case["id"]}', dict(case, **changes))


def environment(name="client-env", marker="env-A", signed=False):
    env = must("POST", "/envs", {"name": name + "-" + SUFFIX + "-" + uuid.uuid4().hex[:5]})
    settings(env["id"], marker, signed)
    return env


def settings(eid, marker, signed=False):
    return must("PUT", f"/envs/{eid}/settings", {
        "sign": {"enabled": signed, "appId": "dummy-app", "appSecret": SIGN_KEY,
                 "nonce": "dummy-nonce", "mgId": "dummy-game"},
        "vars": {"base": [{"name": "endpoint", "value": MOCK}],
                 "header": [{"name": "marker", "value": marker}],
                 "body": [{"name": "marker", "value": marker},
                          {"name": "times", "value": "user-shadow-value"}]}})


def metadata(result):
    value = result.get("metadata") if isinstance(result, dict) else None
    if isinstance(value, str):
        return json.loads(value)
    return value if isinstance(value, dict) else {}


def preview_request(result):
    request = result.get("request", result.get("sent", result))
    return request if isinstance(request, dict) else {}


def send(draft, eid=None):
    return must("POST", "/send", {"spec": draft, "envId": eid})


def step(key, case, eid=None, **changes):
    return dict({"key": key, "name": key, "caseId": case["id"], "envId": eid,
                 "onFailure": "STOP", "independent": False,
                 "cleanup": False, "extractors": []}, **changes)


def extractor(name, path, source="JSON", required=True):
    return {"name": name, "source": source, "path": path, "required": required}


def scenario(name, steps):
    return must("POST", "/scenarios", {"name": name + "-" + SUFFIX, "steps": steps})


def start(scen, repeat=1):
    return must("POST", f'/scenarios/{scen["id"]}/run',
                {"name": "wire-regression-" + SUFFIX, "repeatCount": repeat,
                 "intervalSeconds": 0})


def wait_batch(batch, timeout=20):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = must("GET", f'/batches/{batch["id"]}')
        if not last.get("running") and last.get("batch", {}).get("status") != "RUNNING":
            return last
        # Bounded polling of a state predicate; a fixed delay never proves completion.
        time.sleep(0.03)
    raise AssertionError(f"batch completion deadline exceeded: {last}")


def execute(scen, repeat=1):
    return wait_batch(start(scen, repeat))


def header(entry, name):
    return [value for key, value in entry["headers"] if key.lower() == name.lower()]


def test_preview_temporary():
    reset()
    env = environment(marker="temporary-env")
    before_cases = must("GET", "/cases")
    before_batches = must("GET", "/batches")
    before_history = must("GET", "/execs/recent?limit=100")
    draft = spec("temporary", body='{"marker":"{{marker}}","uuid":"{{$uuid}}"}',
                 url="{{endpoint}}/capture/temporary", headers='{"X-Marker":"{{marker}}"}')
    result = must("POST", "/prepare", {"spec": draft, "envId": env["id"]})
    request = preview_request(result)
    check("预览不发 HTTP", journal() == [])
    check("预览按明确环境解析地址", request.get("url") == MOCK + "/capture/temporary", result)
    check("预览显示解析后的 body", "temporary-env" in (request.get("body") or ""), result)
    check("预览不新增用例和批次", must("GET", "/cases") == before_cases
          and must("GET", "/batches") == before_batches)
    check("预览不写入独立执行历史", must("GET", "/execs/recent?limit=100") == before_history)
    executed = send(draft, env["id"])
    wire = journal()
    check("临时发送仅发一次", len(wire) == 1, wire)
    check("临时发送使用明确环境而非默认环境", len(wire) == 1
          and wire[0]["json"].get("marker") == "temporary-env")
    check("临时发送请求头使用同一环境", len(wire) == 1
          and header(wire[0], "X-Marker") == ["temporary-env"])
    check("临时发送不保存为用例", must("GET", "/cases") == before_cases)
    check("临时发送留存环境快照", metadata(executed).get("envId") == env["id"]
          and metadata(executed).get("envName") == env["name"], metadata(executed))
    bad = must("POST", "/prepare", {"spec": spec("bad-preview", body='{"x":"{{$unknown}}"}'),
                                      "envId": env["id"]})
    check("预览指出受保护未知变量", bool(metadata(bad).get("unresolved")
          or bad.get("unresolved") or bad.get("error")), bad)
    check("失败预览也不发 HTTP", len(journal()) == 1)


def test_dynamic_and_signing():
    reset()
    env = environment(signed=True)
    draft = spec("dynamic-signed", path="/signed?at={{$timestamp}}", headers=json.dumps({
        "X-Time": "{{$timestamp}}", "X-UUID": "{{$uuid}}", "X-Random": "{{$randomInt:1:100}}",
        "authorization": "stale", "AUTHORIZATION": "also-stale"}), body=(
        '{"timestamp":{{$timestamp}},"timeAgain":{{$timestamp}},"ms":{{$timestampMs}},'
        '"uuid":"{{$uuid}}","uuidAgain":"{{$uuid}}","random":{{$randomInt:1:100}},'
        '"randomAgain":{{$randomInt:1:100}},"date":"{{$date:yyyy-MM-dd}}",'
        '"longLiteral":12345678901234567890123456789012345678}'),
        options=options(auth={"type": "INHERIT"}))
    first = send(draft, env["id"])
    second = send(draft, env["id"])
    entries = journal()
    check("收到两条动态签名请求", len(entries) == 2)
    check("独立靶子按实际 body 验签通过", first.get("httpStatus") == 200
          and second.get("httpStatus") == 200 and first.get("verdict") == "PASS", first)
    check("旧签名变量 times 同名遮蔽明确预警", any("times" in str(warning)
          for warning in metadata(first).get("warnings", [])), metadata(first))
    if len(entries) != 2:
        return
    for index, entry in enumerate(entries):
        value = entry["json"] or {}
        prefix = f"请求 {index + 1} "
        check(prefix + "同一时间变量三处一致", value.get("timestamp") == value.get("timeAgain")
              and header(entry, "X-Time") == [str(value.get("timestamp"))]
              and dict(entry["query"]).get("at") == str(value.get("timestamp")), value)
        check(prefix + "同一 UUID 不重复求值", value.get("uuid") == value.get("uuidAgain")
              and header(entry, "X-UUID") == [value.get("uuid")]
              and re.fullmatch(r"[0-9a-fA-F-]{36}", value.get("uuid", "")) is not None)
        check(prefix + "随机数同次一致且在范围内", value.get("random") == value.get("randomAgain")
              and 1 <= value.get("random", -1) <= 100
              and header(entry, "X-Random") == [str(value.get("random"))])
        check(prefix + "毫秒与秒属于同一时刻", abs(value.get("ms", 0) - value.get("timestamp", 0)*1000) < 1000)
        check(prefix + "格式化日期为合法日期", re.fullmatch(r"\d{4}-\d{2}-\d{2}", value.get("date", "")) is not None)
        check(prefix + "38 位 JSON 字面量保精度", value.get("longLiteral") == int(LONG_ID))
        auth = header(entry, "Authorization")
        check(prefix + "鉴权覆盖所有大小写旧值", len(auth) == 1 and auth[0].startswith("Sud-Auth "))
        fields = dict(re.findall(r'(\w+)="([^"]*)"', auth[0] if auth else ""))
        check(prefix + "签名时间与系统变量一致", fields.get("timestamp") == str(value.get("timestamp")))
    check("不同发送重新生成 UUID", entries[0]["json"]["uuid"] != entries[1]["json"]["uuid"])


def test_auth_query_raw_methods():
    reset()
    env = environment(signed=True)
    stale = json.dumps({"authorization": "stale", "AUTHORIZATION": "stale2"})
    auths = [("BEARER", {"type": "BEARER", "token": "dummy-token"}, "Bearer dummy-token"),
             ("BASIC", {"type": "BASIC", "username": "dummy-user", "password": "dummy-pass"},
              "Basic " + base64.b64encode(b"dummy-user:dummy-pass").decode())]
    for name, auth, expected in auths:
        result = send(spec(name, body="{}", headers=stale, options=options(auth=auth)), env["id"])
        check(name + " 鉴权覆盖旧 Authorization", result.get("verdict") == "PASS"
              and header(journal()[-1], "Authorization") == [expected], result)
    result = send(spec("none", body="{}", options=options(auth={"type": "NONE"})), env["id"])
    check("NONE 禁止继承环境签名", result.get("verdict") == "PASS"
          and not header(journal()[-1], "Authorization"))
    send(spec("api-key-header", body="{}", options=options(auth={"type": "API_KEY", "keyName": "X-API-Key",
                                                               "keyValue": "dummy-key", "in": "HEADER"})), env["id"])
    check("API Key header 正确发送", header(journal()[-1], "X-API-Key") == ["dummy-key"])
    send(spec("query", path="/capture?existing=yes", body="{}", options=options(
        params=[{"name": "tag", "value": "a+b &?/#中文", "enabled": True},
                {"name": "tag", "value": "second", "enabled": True},
                {"name": "empty", "value": "", "enabled": True},
                {"name": "disabled", "value": "never", "enabled": False}],
        auth={"type": "API_KEY", "keyName": "key", "keyValue": "a+b&中文", "in": "QUERY"})), env["id"])
    q = journal()[-1]["query"]
    check("query 特殊字符、重复键、空值和鉴权编码正确", q == [["existing", "yes"],
          ["tag", "a+b &?/#中文"], ["tag", "second"], ["empty", ""], ["key", "a+b&中文"]], q)
    raw = 'not-json: 中文\n  "text" \\tail'
    result = send(spec("raw", body=raw, options=options(bodyType="RAW")), env["id"])
    check("RAW body 逐字节保持原文", result.get("verdict") == "PASS"
          and base64.b64decode(journal()[-1]["bodyBase64"]) == raw.encode())
    send(spec("none-body", body="ignored", options=options(bodyType="NONE")), env["id"])
    check("NONE body 不发送残留文本", journal()[-1]["bodyBytes"] == 0)
    for method in ("HEAD", "OPTIONS"):
        result = send(spec(method, method=method, options=options(bodyType="NONE")), env["id"])
        check(method + " 通用 HTTP 方法发送成功", result.get("httpStatus") == 200
              and journal()[-1]["method"] == method, result)


def test_protected_and_legacy():
    reset()
    env = environment(signed=False)
    result = send(spec("legacy", headers='{"X-Legacy":"{{times}}","X-Unknown":"{{unregistered}}"}',
                       body='{"legacy":"{{unregistered}}"}'), env["id"])
    check("旧未知占位符兼容原样发送", result.get("verdict") == "PASS"
          and journal()[0]["json"]["legacy"] == "{{unregistered}}", result)
    check("旧未知占位符产生警告", bool(metadata(result).get("warnings")), metadata(result))
    for name in ("$unknown", "run.missing"):
        count = len(journal())
        status, result = api("POST", "/send", {"spec": spec("protected", body='{"x":"{{' + name + '}}"}'),
                                                "envId": env["id"]})
        check(name + " 未定义明确失败", status >= 400 or result.get("verdict") == "ERROR", result)
        check(name + " 未定义绝不发 HTTP", len(journal()) == count)
    status, result = api("POST", "/send", {"spec": spec("bad-env", body="{}"), "envId": 999999999})
    check("未知临时环境拒绝且不回退默认环境", (status >= 400 or result.get("verdict") == "ERROR")
          and len(journal()) == 1, result)


def test_chain_typed_and_rounds():
    reset()
    env = environment()
    issue = create_case(spec("typed-issue", "/issue?scope=typed"))
    verify = create_case(spec("typed-verify", "/verify?scope=typed", body=(
        '{"id":"{{run.id}}","number":{{run.number}},"token":"{{run.token}}",'
        '"text":"{{run.text}}","flag":{{run.flag}},"object":{{run.object}},'
        '"array":{{run.array}},"header":"{{run.headerToken}}"}')))
    extractors = [extractor(k, "data." + k) for k in
                  ("id", "number", "token", "text", "flag", "object", "array")]
    extractors.append(extractor("headerToken", "X-Chain-Token", "HEADER"))
    scen = scenario("typed-chain", [step("issue", issue, env["id"], extractors=extractors),
                                    step("consume", verify, env["id"])])
    result = execute(scen, 2)
    entries = journal()
    check("请求链两轮严格按保存顺序执行", [e["path"] for e in entries]
          == ["/issue", "/verify", "/issue", "/verify"], entries)
    check("两轮链路全部通过", len(result.get("execs", [])) == 4
          and all(e.get("verdict") == "PASS" for e in result["execs"]), result)
    consumes = [e for e in entries if e["path"] == "/verify"]
    check("链变量每轮重新赋值无串轮", len(consumes) == 2
          and consumes[0]["json"]["token"] == "typed-token-1"
          and consumes[1]["json"]["token"] == "typed-token-2")
    for index, entry in enumerate(consumes):
        data = entry["json"]
        check(f"第 {index+1} 轮 typed JSON 长整数保精度", data["number"] == int(LONG_ID)+index+1
              and data["id"] == str(int(LONG_ID)+index+1), data)
        check(f"第 {index+1} 轮字符串转义与中文保持原值", data["text"] == SPECIAL, data)
        check(f"第 {index+1} 轮对象数组布尔保类型", data["flag"] is True
              and data["object"] == {"nested": "value"} and data["array"] == [1, "x", False])
        check(f"第 {index+1} 轮响应头提取可供下一步使用", data["header"] == data["token"])
    check("执行记录保存提取来源和结果", bool(metadata(result["execs"][0]).get("extractions")),
          metadata(result["execs"][0]))
    return scen


def test_missing_cleanup_continue():
    reset()
    env = environment()
    issue = create_case(spec("missing-issue", "/issue?scope=missing&missingAt=2"))
    consume = create_case(spec("missing-consume", "/verify?scope=missing",
                               body='{"id":"{{run.id}}","token":"{{run.token}}"}'))
    cleanup_fail = create_case(spec("cleanup-fail", "/cleanup-fail"))
    cleanup = create_case(spec("cleanup-after-fail", "/cleanup", body='{"safe":true}'))
    scen = scenario("missing-and-cleanup", [
        step("issue", issue, env["id"], extractors=[extractor("id", "data.id"), extractor("token", "data.token")]),
        step("consume", consume, env["id"]),
        step("cleanup-fail", cleanup_fail, env["id"], cleanup=True, onFailure="CONTINUE"),
        step("cleanup-last", cleanup, env["id"], cleanup=True)])
    result = execute(scen, 2)
    entries = journal()
    consumes = [e for e in entries if e["path"] == "/verify"]
    check("第二轮提取失败不使用上一轮 token", len(consumes) == 1
          and consumes[0]["json"]["token"] == "missing-token-1", entries)
    check("提取失败后的依赖步骤明确 SKIP", any(e.get("roundNo") == 2 and e.get("verdict") == "SKIP"
          for e in result.get("execs", [])), result)
    check("SKIP 计入独立批次统计", (result["batch"].get("skippedExecs") or 0) >= 1, result["batch"])
    check("两个清理步骤每轮都尝试，前一清理失败不阻止后一条", [e["path"] for e in entries].count("/cleanup-fail") == 2
          and [e["path"] for e in entries].count("/cleanup") == 2, entries)
    fail = create_case(spec("continue-failure", "/fail"))
    independent = create_case(spec("continue-independent", "/capture/independent"))
    continued = scenario("continue-policy", [step("failure", fail, env["id"], onFailure="CONTINUE"),
                                             step("independent", independent, env["id"])])
    reset()
    result = execute(continued)
    check("CONTINUE 策略允许普通后续步骤继续", [e["path"] for e in journal()] == ["/fail", "/capture/independent"]
          and result["execs"][-1]["verdict"] == "PASS", result)
    stopped = scenario("stop-independent-policy", [step("failure", fail, env["id"]),
        step("dependent", consume, env["id"]),
        step("independent", independent, env["id"], independent=True),
        step("cleanup", cleanup, env["id"], cleanup=True)])
    reset()
    result = execute(stopped)
    check("STOP 跳过依赖步骤但保留显式 independent 与 cleanup", [e["path"] for e in journal()]
          == ["/fail", "/capture/independent", "/cleanup"], journal())
    check("独立步骤不复用 CONTINUE 作为独立标记", [e.get("verdict") for e in result["execs"]]
          == ["FAIL", "SKIP", "PASS", "PASS"], result)


def test_configuration_snapshot():
    reset()
    env = environment(marker="snapshot-old")
    other = environment(marker="different-env")
    group = must("POST", "/case-groups", {"name": "snapshot-group-" + SUFFIX, "envId": env["id"]})
    gate = create_case(spec("snapshot-gate", "/gate?id=snapshot"))
    target = create_case(spec("snapshot-target", "/capture/snapshot-old", body='{"marker":"{{marker}}","revision":"old"}',
                              groupId=group["id"], headers='{"X-Marker":"{{marker}}"}'))
    scen = scenario("snapshot", [step("gate", gate, env["id"]), step("target", target)])
    batch = start(scen)
    control("gate-wait?id=snapshot&timeout=10")
    settings(env["id"], "snapshot-new")
    must("PUT", f'/case-groups/{group["id"]}', {"name": group["name"], "envId": other["id"]})
    update_case(target, url=MOCK + "/capture/snapshot-new", body='{"marker":"{{marker}}","revision":"new"}')
    control("gate-release?id=snapshot", {})
    result = wait_batch(batch)
    wire = journal()
    last = wire[-1]
    check("运行开始固定后续请求用例内容", last["path"] == "/capture/snapshot-old"
          and last["json"].get("revision") == "old", wire)
    check("运行开始固定变量与环境绑定", last["json"].get("marker") == "snapshot-old"
          and header(last, "X-Marker") == ["snapshot-old"], wire)
    stored = result["execs"][-1]
    check("历史记录保存启动时环境身份", metadata(stored).get("envId") == env["id"]
          and metadata(stored).get("envName") == env["name"], metadata(stored))
    captures = [metadata(e).get("capturedAt") for e in result["execs"]]
    check("同一次场景执行共享配置快照时间", all(captures) and len(set(captures)) == 1, captures)
    history_id = batch["id"]
    reset()
    # The gate is released by the test, not by an elapsed-time assumption.
    again = start(scen)
    control("gate-wait?id=snapshot&timeout=10")
    control("gate-release?id=snapshot", {})
    wait_batch(again)
    new = journal()[-1]
    check("下次运行采用已修改配置", new["path"] == "/capture/snapshot-new"
          and new["json"].get("marker") == "different-env"
          and new["json"].get("revision") == "new", new)
    must("PUT", f'/envs/{env["id"]}', {"name": "renamed-after-run-" + SUFFIX})
    old_history = must("GET", f"/batches/{history_id}")["execs"][-1]
    check("环境重命名不改写历史名称", metadata(old_history).get("envName") == env["name"], metadata(old_history))


def test_stop_cleanup():
    reset()
    env = environment()
    gate = create_case(spec("stop-gate", "/gate?id=stop"))
    normal = create_case(spec("stop-normal", "/capture/should-not-send"))
    cleanup = create_case(spec("stop-cleanup", "/cleanup"))
    scen = scenario("stop-cleanup", [step("gate", gate, env["id"]), step("normal", normal, env["id"]),
                                     step("cleanup", cleanup, env["id"], cleanup=True)])
    batch = start(scen)
    control("gate-wait?id=stop&timeout=10")
    must("POST", f'/batches/{batch["id"]}/stop', {})
    control("gate-release?id=stop", {})
    result = wait_batch(batch)
    check("停止后不再发普通步骤", "/capture/should-not-send" not in [e["path"] for e in journal()])
    check("停止仍执行清理步骤", [e["path"] for e in journal()] == ["/gate", "/cleanup"], journal())
    check("停止状态可观察", result["batch"]["status"] == "STOPPED" and not result.get("running"), result)


def test_assertions_and_truncation():
    reset()
    env = environment()
    rules = [{"path": "data.code", "op": "regex", "expect": "^ROOM-[0-9]+$"},
             {"path": "header:x-mixed-case", "op": "equals", "expect": "value-123"},
             {"path": "header:Content-Type", "op": "contains", "expect": "application/json"},
             {"path": "$elapsedMs", "op": "lt", "expect": "5000"}]
    result = send(spec("assert-all", "/headers", asserts=json.dumps(rules)), env["id"])
    check("正则、响应头大小写和耗时断言全部通过", result.get("verdict") == "PASS", result)
    judged = json.loads(result.get("assertsResult") or "[]")
    check("各断言逐条留痕", len(judged) == len(rules), judged)
    result = send(spec("regex-bad", "/headers", asserts=json.dumps(
        [{"path": "data.code", "op": "regex", "expect": "["}])), env["id"])
    check("非法正则明确失败而非崩溃或静默通过", result.get("verdict") in ("FAIL", "ERROR")
          and bool(result.get("reason") or result.get("error")), result)
    result = send(spec("regex-false", "/headers", asserts=json.dumps(
        [{"path": "data.code", "op": "regex", "expect": "^NOPE$"},
         {"path": "header:X-Missing", "op": "exists", "expect": ""}])), env["id"])
    check("正则反例和缺少响应头不能假绿", result.get("verdict") == "FAIL", result)
    result = send(spec("elapsed-false", "/delay?ms=160", asserts=json.dumps(
        [{"path": "$elapsedMs", "op": "lt", "expect": "80"}])), env["id"])
    check("耗时断言失败与请求超时分开", result.get("httpStatus") == 200
          and result.get("verdict") == "FAIL" and result.get("elapsedMs", 0) >= 100, result)
    result = send(spec("huge-response", "/huge", asserts=json.dumps(
        [{"path": "tail", "op": "equals", "expect": LONG_ID}]),
        options=options(extractors=[extractor("tail", "tail")])), env["id"])
    meta = metadata(result)
    check("超长响应截断前完成断言", result.get("verdict") == "PASS", result)
    check("超长响应截断前完成提取", LONG_ID in json.dumps(meta.get("extractions", [])), meta)
    check("响应截断标识和原长完整", meta.get("responseBodyTruncated") is True
          and meta.get("responseBodyChars", 0) > 31000, meta)
    huge = json.dumps({"pad": "x"*31000, "tail": LONG_ID}, separators=(",", ":"))
    result = send(spec("huge-request", body=huge), env["id"])
    meta = metadata(result)
    check("超长请求真实发送完整 body", journal()[-1]["body"] == huge)
    check("请求截断标识和原长完整", meta.get("requestBodyTruncated") is True
          and meta.get("requestBodyChars") == len(huge), meta)
    result = send(spec("short-body", body="{}"), env["id"])
    meta = metadata(result)
    check("短请求响应截断标识明确为 false", meta.get("requestBodyTruncated") is False
          and meta.get("responseBodyTruncated") is False, meta)


def test_concurrent_isolation():
    reset()
    scenarios = []
    for name in ("isolation-A", "isolation-B"):
        env = environment(marker=name)
        issue = create_case(spec(name+"-issue", "/issue?scope="+name))
        gate = create_case(spec(name+"-gate", "/gate?id="+name))
        consume = create_case(spec(name+"-consume", "/verify?scope="+name,
                                   body='{"id":"{{run.id}}","token":"{{run.token}}","marker":"{{marker}}"}'))
        scenarios.append(scenario(name, [step("issue", issue, env["id"], extractors=[
            extractor("id", "data.id"), extractor("token", "data.token")]),
            step("gate", gate, env["id"]), step("consume", consume, env["id"])]))
    batches = [start(s) for s in scenarios]
    for name in ("isolation-A", "isolation-B"):
        control("gate-wait?id="+name+"&timeout=10")
    control("gate-release?id=isolation-B", {})
    b = wait_batch(batches[1])
    control("gate-release?id=isolation-A", {})
    a = wait_batch(batches[0])
    consumes = [e["json"] for e in journal() if e["path"] == "/verify"]
    check("并发场景各自通过", all(e["verdict"] == "PASS" for e in a["execs"]+b["execs"]))
    check("并发同名链变量与环境隔离", len(consumes) == 2 and
          {v["token"] for v in consumes} == {"isolation-A-token-1", "isolation-B-token-1"}
          and all(v["token"].startswith(v["marker"]) for v in consumes), consumes)
    # Same scenario, same variable names, different environments must not share values.
    reset()
    env_a, env_b = environment(marker="cross-A"), environment(marker="cross-B")
    persisted_before = must("GET", f'/vars?env={env_a["id"]}')
    issue = create_case(spec("cross-env-issue", "/issue?scope=cross-env"))
    consume = create_case(spec("cross-env-consume", "/verify?scope=cross-env",
                               body='{"id":"{{run.id}}","token":"{{run.token}}"}'))
    cross = scenario("cross-environment", [step("issue", issue, env_a["id"], extractors=[
        extractor("id", "data.id"), extractor("token", "data.token")]),
        step("consume", consume, env_b["id"])])
    result = execute(cross)
    check("同一场景跨环境不能隐式带入链变量", [e["path"] for e in journal()] == ["/issue"]
          and result["execs"][-1]["verdict"] == "ERROR", result)
    check("运行提取不写入环境持久变量", must("GET", f'/vars?env={env_a["id"]}') == persisted_before)


def test_legacy_execution():
    reset()
    env = environment(signed=True)
    group = must("POST", "/case-groups", {"name": "legacy-group-"+SUFFIX, "envId": env["id"]})
    draft = spec("legacy-no-options", "/signed", body='{"app":"{{app_id}}","game":"{{mg_id}}"}',
                 groupId=group["id"])
    del draft["options"]
    saved = create_case(draft)
    result = must("POST", "/run", {"caseIds": [saved["id"]]})
    check("旧用例无 options 仍继承环境签名", len(result) == 1 and result[0].get("verdict") == "PASS"
          and len(journal()) == 1 and header(journal()[0], "Authorization")[0].startswith("Sud-Auth "), result)
    check("旧占位符仍按业务签名凭证解析", journal()[0]["json"] == {"app": "dummy-app", "game": "dummy-game"})
    batch = must("POST", "/batches", {"name": "legacy-batch-"+SUFFIX,
        "caseIds": [saved["id"]], "repeatCount": 2, "intervalSeconds": 0})
    result = wait_batch(batch)
    check("旧批量入口两轮发送与记录一一对应", len(result.get("execs", [])) == 2
          and result["batch"].get("totalExecs") == 2 and len(journal()) == 3
          and all(e.get("verdict") == "PASS" for e in result["execs"]), result)


def save_restart_fixture(manifest):
    env = environment(marker="restart-marker")
    issue = create_case(spec("restart-issue", "/issue?scope=restart"))
    consume = create_case(spec("restart-consume", "/verify?scope=restart",
                               body='{"id":"{{run.id}}","token":"{{run.token}}"}'))
    scen = scenario("restart-scenario", [step("issue", issue, env["id"], extractors=[
        extractor("id", "data.id"), extractor("token", "data.token")]),
        step("consume", consume, env["id"], onFailure="CONTINUE")])
    scenarios = must("GET", "/scenarios")
    stored = next((s for s in scenarios if s.get("id") == scen["id"]), None)
    check("场景保存与列表往返完整", stored == scen, (stored, scen))
    renamed = dict(scen, name=scen["name"]+"-saved")
    scen = must("PUT", f'/scenarios/{scen["id"]}', renamed)
    delete_me = scenario("delete-fixture", [step("unused", issue, env["id"])])
    must("DELETE", f'/scenarios/{delete_me["id"]}')
    check("删除场景不会删除复用用例", must("GET", f'/cases/{issue["id"]}')["id"] == issue["id"])
    manifest.write_text(json.dumps({"scenario": scen, "env": env,
                                    "case": consume}, ensure_ascii=False, indent=2))


def test_restart(manifest):
    saved = json.loads(manifest.read_text())
    scenarios = must("GET", "/scenarios")
    scen = next((s for s in scenarios if s.get("id") == saved["scenario"]["id"]), None)
    check("重启后场景步骤、顺序和策略完整", scen == saved["scenario"], (scen, saved["scenario"]))
    persisted = must("GET", f'/cases/{saved["case"]["id"]}')
    check("重启后请求 options 与 body 完整", persisted.get("options") == saved["case"].get("options")
          and persisted.get("body") == saved["case"].get("body"), persisted)
    reset()
    result = execute(scen)
    check("重启后保存场景仍能真实执行", len(result.get("execs", [])) == 2
          and all(e.get("verdict") == "PASS" for e in result["execs"]), result)
    check("重启后请求没有重复迁移或重复发送", [e["path"] for e in journal()] == ["/issue", "/verify"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("exercise", "restart"), default="exercise")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    args = parser.parse_args()
    loopback(BASE)
    loopback(MOCK)
    devices = call("GET", BASE.removesuffix("/api/apitest") + "/api/devices")[1]
    if devices != []:
        raise RuntimeError("隔离实例必须返回空设备列表；拒绝继续可能触碰真机的验证")
    if args.phase == "exercise":
        for name, function in [
            ("预览与临时发送", test_preview_temporary),
            ("动态变量与实际签名", test_dynamic_and_signing),
            ("鉴权、参数、原始体与方法", test_auth_query_raw_methods),
            ("保护变量与旧写法", test_protected_and_legacy),
            ("typed JSON 与两轮链路", test_chain_typed_and_rounds),
            ("提取失败、清理与继续", test_missing_cleanup_continue),
            ("整体配置快照与历史", test_configuration_snapshot),
            ("停止与清理", test_stop_cleanup),
            ("断言与完整请求响应", test_assertions_and_truncation),
            ("并发运行隔离", test_concurrent_isolation),
            ("旧执行与批量入口", test_legacy_execution),
            ("保存重启夹具", lambda: save_restart_fixture(args.manifest))]:
            section(name, function)
    else:
        section("重启持久化", lambda: test_restart(args.manifest))
    failed = sum(not c["passed"] for c in CHECKS)
    result = {"phase": args.phase, "total": len(CHECKS), "passed": len(CHECKS)-failed,
              "failed": failed, "sections": SECTIONS, "checks": CHECKS}
    args.summary.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(f'\nHTTP 客户端专项 {args.phase}: {result["passed"]}/{result["total"]}，失败 {failed}', flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
