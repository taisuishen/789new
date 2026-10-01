#!/usr/bin/env python3
"""Local dev server for bingo-web: serves the static pages and proxies /api/** to bingo-gateway.

Same origin for pages and API, so the gateway needs no CORS setup locally.
Listens on every interface by default so a phone on the same Wi-Fi can open it (the LAN addresses are printed);
HOST=127.0.0.1 keeps it to this machine.

    python3 bingo-web/dev_server.py                      # :5173 -> gateway http://127.0.0.1:8090
    GATEWAY_URL=http://127.0.0.1:8080 PORT=5000 HOST=127.0.0.1 python3 bingo-web/dev_server.py
"""
import http.client
import os
import socket
import urllib.parse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
PORT = int(os.environ.get("PORT", "5173"))
HOST = os.environ.get("HOST", "0.0.0.0")
GATEWAY = urllib.parse.urlsplit(os.environ.get("GATEWAY_URL", "http://127.0.0.1:8090"))
HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers",
              "transfer-encoding", "upgrade", "host", "content-length"}


class Handler(SimpleHTTPRequestHandler):

    def do_GET(self):
        self._proxy() if self.path.startswith("/api/") else super().do_GET()

    def do_POST(self):
        self._proxy() if self.path.startswith("/api/") else self.send_error(405)

    do_PUT = do_DELETE = do_PATCH = do_POST

    def end_headers(self):
        if not self.path.startswith("/api/"):
            self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _proxy(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        conn_cls = http.client.HTTPSConnection if GATEWAY.scheme == "https" else http.client.HTTPConnection
        conn = conn_cls(GATEWAY.hostname, GATEWAY.port, timeout=30)
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            upstream = conn.getresponse()
            data = upstream.read()
        except OSError as e:
            self.send_response(502)
            payload = ('{"code":10503,"message":"gateway not reachable at %s (%s)","data":null}'
                       % (GATEWAY.geturl(), e.__class__.__name__)).encode()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        finally:
            conn.close()
        self.send_response(upstream.status)
        for k, v in upstream.getheaders():
            if k.lower() not in HOP_BY_HOP:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def lan_addresses():
    """IPv4 addresses of this machine on the local network (the one with the default route first)."""
    found = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 80))  # TEST-NET address: no packet is sent, only the route is resolved
            found.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if info[4][0] not in found and not info[4][0].startswith("127."):
                found.append(info[4][0])
    except OSError:
        pass
    return found


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), partial(Handler, directory=ROOT))
    print(f"bingo-web  (api -> {GATEWAY.geturl()})", flush=True)
    print(f"  this machine: http://127.0.0.1:{PORT}", flush=True)
    if HOST == "0.0.0.0":
        for ip in lan_addresses():
            print(f"  phone (same Wi-Fi): http://{ip}:{PORT}", flush=True)
    server.serve_forever()
