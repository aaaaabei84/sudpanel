#!/usr/bin/env python3
"""Loopback-only wire oracle for the HTTP client regression suite."""
import argparse
import base64
import hashlib
import hmac
import json
import re
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LONG_ID = "12345678901234567890123456789012345678"
SPECIAL = '中文 "quoted" \\slash\nnext line\tend'
SIGN_KEY = "dummy-client-regression-key"
CONDITION = threading.Condition()
JOURNAL = []
ISSUED = {}
GATES = {}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def respond(self, status=200, payload=None, headers=None, raw=None):
        data = raw if raw is not None else json.dumps(
            payload if payload is not None else {"ret_code": 0},
            ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        for key, value in (headers or []):
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def handle_request(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        one = lambda key, default="": query.get(key, [default])[0]
        if path.startswith("/_control/"):
            return self.control(path, query, body)
        try:
            decoded = json.loads(body) if body else None
        except (ValueError, UnicodeDecodeError):
            decoded = None
        with CONDITION:
            entry = {"seq": len(JOURNAL) + 1, "method": self.command,
                     "path": path, "query": urllib.parse.parse_qsl(parsed.query, keep_blank_values=True),
                     "target": self.path, "headers": list(self.headers.raw_items()),
                     "body": body.decode("utf-8", "replace"),
                     "bodyBase64": base64.b64encode(body).decode("ascii"),
                     "bodyBytes": len(body), "json": decoded}
            JOURNAL.append(entry)
            CONDITION.notify_all()
        if path == "/issue":
            scope = one("scope", "default")
            with CONDITION:
                issued = ISSUED.setdefault(scope, [])
                ordinal = len(issued) + 1
                value = {"id": str(int(LONG_ID) + ordinal), "number": int(LONG_ID) + ordinal,
                         "token": f"{scope}-token-{ordinal}", "text": SPECIAL,
                         "flag": True, "nil": None, "object": {"nested": "value"},
                         "array": [1, "x", False]}
                issued.append(value)
            if one("missingAt") == str(ordinal):
                value = {"missing": True}
            return self.respond(payload={"ret_code": 0, "data": value},
                                headers=[("X-Chain-Token", value.get("token", ""))])
        if path == "/verify":
            scope = one("scope", "default")
            with CONDITION:
                issued = list(ISSUED.get(scope, []))
            found = next((v for v in issued if isinstance(decoded, dict)
                          and decoded.get("token") == v["token"]), None)
            valid = found is not None and decoded.get("id") == found["id"]
            return self.respond(200 if valid else 409,
                                {"ret_code": 0 if valid else 409, "valid": valid,
                                 "received": decoded})
        if path == "/gate":
            gate_id = one("id", "default")
            with CONDITION:
                event = GATES.setdefault(gate_id, threading.Event())
                CONDITION.notify_all()
            released = event.wait(20)
            return self.respond(200 if released else 504,
                                {"ret_code": 0 if released else 504, "released": released})
        if path == "/signed":
            auth = self.headers.get("Authorization", "")
            fields = dict(re.findall(r'(\w+)="([^"]*)"', auth))
            material = (fields.get("app_id", "") + "\n" + fields.get("timestamp", "")
                        + "\n" + fields.get("nonce", "") + "\n").encode() + body + b"\n"
            expected = hmac.new(SIGN_KEY.encode(), material, hashlib.sha1).hexdigest()
            valid = auth.startswith("Sud-Auth ") and hmac.compare_digest(
                fields.get("signature", ""), expected)
            return self.respond(200 if valid else 401,
                                {"ret_code": 0 if valid else 401, "valid": valid})
        if path == "/headers":
            return self.respond(payload={"ret_code": 0, "data": {"code": "ROOM-123"}},
                                headers=[("X-Mixed-Case", "value-123"),
                                         ("X-Multi", "one"), ("X-Multi", "two")])
        if path == "/delay":
            threading.Event().wait(min(float(one("ms", "150")) / 1000, 3))
            return self.respond()
        if path == "/huge":
            return self.respond(payload={"ret_code": 0, "pad": "x" * 31000,
                                         "tail": LONG_ID})
        if path == "/fail" or path == "/cleanup-fail":
            return self.respond(500, {"ret_code": 500, "message": "intentional fixture failure"})
        if path.startswith("/capture") or path == "/cleanup":
            return self.respond(payload={"ret_code": 0, "received": decoded,
                                         "bodyBytes": len(body)})
        return self.respond(404, {"ret_code": 404, "message": "unknown fixture route"})

    def control(self, path, query, body):
        one = lambda key, default="": query.get(key, [default])[0]
        if path == "/_control/ready":
            return self.respond(payload={"ready": True})
        if path == "/_control/journal":
            with CONDITION:
                entries = list(JOURNAL)
            return self.respond(payload=entries)
        if path == "/_control/reset" and self.command == "POST":
            with CONDITION:
                for event in GATES.values():
                    event.set()
                JOURNAL.clear()
                ISSUED.clear()
                GATES.clear()
            return self.respond(payload={"reset": True})
        if path == "/_control/gate-wait":
            gate_id = one("id", "default")
            deadline = time.monotonic() + min(float(one("timeout", "10")), 20)
            with CONDITION:
                while gate_id not in GATES and time.monotonic() < deadline:
                    CONDITION.wait(max(0.001, deadline - time.monotonic()))
                arrived = gate_id in GATES
            return self.respond(200 if arrived else 408, {"arrived": arrived})
        if path == "/_control/gate-release" and self.command == "POST":
            gate_id = one("id", "default")
            with CONDITION:
                event = GATES.get(gate_id)
            if event:
                event.set()
            return self.respond(200 if event else 404, {"released": bool(event)})
        return self.respond(404, {"message": "unknown control route"})

    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request
    do_PATCH = handle_request
    do_DELETE = handle_request
    do_HEAD = handle_request
    do_OPTIONS = handle_request

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18889)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print(json.dumps({"ready": True, "port": args.port}), flush=True)
    server.serve_forever()
