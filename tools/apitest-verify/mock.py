#!/usr/bin/env python3
"""接口测试功能的验证靶子：一个本地 mock 服务。

只监听 127.0.0.1，不连任何外部地址 —— 验证过程绝不碰真实内网环境。
每个路径刻意造出一种需要区分的情况：
  /ok        正常返回，业务码 0
  /bizfail    HTTP 200 但业务码是失败码（最容易漏测的一类：接口通了、业务没过）
  /http500    HTTP 5xx
  /nested     业务码包在 data 里
  /text       非 JSON 响应（验证「不瞎抓返回码」）
  /slow       慢响应（验证超时路径）
  /count      每次调用自增，用来数「定时批量到底跑了几轮」
  /long       超长且不含空格的字符串值（验证结果展示的自动折行：
              没有可断行的位置时，只能靠 overflow-wrap:anywhere 才不会被顶出容器）
  /profile    嵌套结构（2026-10-05，需求 #51 响应体断言的靶子）
  /huge       超过 BODY_LIMIT 的响应体（验「断言在截断前判定」）
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COUNTER = {"n": 0}
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, payload, ctype="application/json"):
        body = payload.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # 无论走哪个分支，都必须先把请求体读干净。
        # 踩坑记录：早先只在 /ok 分支里读 body，于是「POST /bizfail」的请求体残留在
        # 连接缓冲区里，被 HTTP/1.1 keep-alive 复用连接时的下一条请求当成请求行解析，
        # 服务器就回了个 501「不支持的方法」——看着像被测程序发错了方法，实际是靶子没排空。
        # 这类问题会污染整轮验证结论，所以放在最前面统一处理。
        body_len = self._body_len()
        path = self.path.split("?")[0]
        if path == "/ok":
            # 回显请求体长度，便于确认「请求体真的发出去了」
            self._send(200, json.dumps({"ret_code": 0, "msg": "成功", "got": body_len}))
        elif path == "/bizfail":
            self._send(200, json.dumps({"ret_code": 100501, "msg": "游戏已开始，不能重复开始"}))
        elif path == "/http500":
            self._send(500, json.dumps({"msg": "internal boom"}))
        elif path == "/nested":
            self._send(200, json.dumps({"ok": True, "data": {"code": 101004}}))
        elif path == "/text":
            self._send(200, "plain text, not json", "text/plain")
        elif path == "/slow":
            time.sleep(3)
            self._send(200, json.dumps({"ret_code": 0, "msg": "慢但成功"}))
        elif path == "/count":
            with LOCK:
                COUNTER["n"] += 1
                n = COUNTER["n"]
            self._send(200, json.dumps({"ret_code": 0, "count": n}))
        elif path == "/long":
            # 一段没有任何空格的长串（模拟签名、token、base64 这类值）：
            # 展示时只能靠任意位置断行，按空格断行的写法会把它顶出容器
            blob = "A1B2C3D4E5F6G7H8" * 18
            self._send(200, json.dumps({"ret_code": 0, "msg": "长串", "blob": blob}))
        elif path == "/profile":
            # 专供响应体断言用的一棵嵌套结构（需求 #51）。
            # 每个字段对应一类断言场景，验的是「断得对不对」，不是接口本身能不能通。
            self._send(200, json.dumps({
                "ret_code": 0,
                "data": {
                    "roomId": 10086,          # 整数：type=integer 正例、type=string 反例
                    "roomName": "开黑房",      # 文本：equals / contains / length
                    "ratio": 1.0,             # 带小数点的 number：number 过、integer 不过
                    "tags": ["a", "b"],       # 数组：length=2、[*] 全部是 string
                    "empty": [],              # 空数组：[*] 必须判失败，不能空集恒真
                    "note": None,             # 值为 null：与「键不存在」分开报
                    "players": [
                        {"uid": 1, "seat": 3, "ready": True},
                        {"uid": 2, "seat": 1, "ready": False},
                        {"uid": 3, "seat": 2, "ready": True},
                    ],
                },
            }, ensure_ascii=False))
        elif path == "/huge":
            # 超过 ApiExec.BODY_LIMIT(20000) 的响应体：用来验「断言是在截断前判定的」。
            # tail 故意放在 30000 字符之后 —— 若断言被挪到读库（已截断）之后再判，
            # 它要么找不到、要么整段 JSON 都解析不了，表现成「接口明明是对的却红了」。
            self._send(200, json.dumps({
                "ret_code": 0, "msg": "超长", "pad": "x" * 30000, "tail": "END",
            }))
        elif path == "/count/reset":
            with LOCK:
                COUNTER["n"] = 0
            self._send(200, json.dumps({"ret_code": 0, "count": 0}))
        else:
            self._send(404, json.dumps({"ret_code": 404, "msg": "no such path"}))

    do_POST = do_GET
    do_PUT = do_GET

    def _body_len(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            self.rfile.read(n)
        return n

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 18888), Handler).serve_forever()
