#!/usr/bin/env python3
"""把请求「原样吐出来」的回显服务器 —— 用来对比两个客户端（比如 Postman 与 SudPanel）到底发了什么。

背景：签名类接口（Sud-Auth）失败时，看响应只能知道「认证失败」，看不出是哪个字节不一样。
把两边都指向这个回显服务器，就能拿到逐字节的原文来比对。

用法：
    python3 tools/apitest-verify/echo-server.py            # 默认 127.0.0.1:18889
    python3 tools/apitest-verify/echo-server.py 18999      # 换端口

每次请求会在 tools/apitest-verify/echo-out/ 下落两个文件：
    req-<序号>.txt    方法 / 路径 / 全部请求头 / 请求体的 repr（能看见空格与转义）
    req-<序号>.body   请求体的原始字节（用于 diff）

提示：地址一定写 127.0.0.1，不要写 localhost —— 本机设着 HTTP_PROXY，
写 localhost 有可能被代理接管，看到的就不是你自己的请求了。
"""

import os
import sys
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "echo-out")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18889
_seq = 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _dump(self, method, body: bytes):
        global _seq
        _seq += 1
        os.makedirs(OUT_DIR, exist_ok=True)
        txt = os.path.join(OUT_DIR, f"req-{_seq:03d}.txt")
        raw = os.path.join(OUT_DIR, f"req-{_seq:03d}.body")

        lines = [f"{method} {self.path}", f"来自: {self.client_address[0]}:{self.client_address[1]}", "", "请求头："]
        # 原样打印，不做任何排序/归一化 —— 差异往往就藏在大小写和顺序里
        for k, v in self.headers.items():
            lines.append(f"  {k}: {v}")
        lines += [
            "",
            f"请求体长度（字节）= {len(body)}",
            "请求体 repr（能看见空格/换行/转义）：",
            repr(body),
            "",
            f"原始字节已另存：{os.path.relpath(raw, os.getcwd())}",
        ]
        text = "\n".join(lines) + "\n"
        with open(txt, "w", encoding="utf-8") as f:
            f.write(text)
        with open(raw, "wb") as f:
            f.write(body)

        print(f"\n===== #{_seq:03d} {method} {self.path}  (body {len(body)} 字节) =====")
        print(text, end="")

    def _body(self) -> bytes:
        # 无论走哪个分支都要先把请求体读完 —— 否则残留字节会被 keep-alive 的下一条请求
        # 当成请求行解析，回一个莫名其妙的 501（这个坑在 mock.py 上踩过一次）
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n > 0 else b""

    def _respond(self):
        payload = b'{"ret_code":0,"ret_msg":"echo-ok"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self._dump("GET", self._body())
        self._respond()

    def do_POST(self):
        self._dump("POST", self._body())
        self._respond()

    do_PUT = do_POST
    do_PATCH = do_POST
    do_DELETE = do_POST
    do_HEAD = do_GET
    do_OPTIONS = do_GET

    def log_message(self, *a):
        pass  # 关掉默认访问日志，只留我们自己打印的


def main():
    if not 1024 < PORT < 65536:
        print(f"端口不合法：{PORT}", file=sys.stderr)
        return 1
    os.makedirs(OUT_DIR, exist_ok=True)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"回显服务器已启动：http://127.0.0.1:{PORT}/")
    print(f"输出目录：{OUT_DIR}")
    print("把 Postman 和 SudPanel 的地址都指到这里，各发一次，然后比对 req-*.txt")
    print("Ctrl+C 结束。")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    except Exception:
        traceback.print_exc()
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
