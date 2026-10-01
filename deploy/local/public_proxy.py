#!/usr/bin/env python3
"""LOCAL DEVELOPMENT ONLY: the one thing the public tunnel (deploy/local/tunnel.sh) reaches.

It answers exactly two things and 404s everything else, so the internet never reaches a bingo service directly
(bingo-kyc trusts the identity headers the gateway sets: exposed as is, anyone could act as any player):

  POST /callback/runpod/kyc?token=...       -> bingo-kyc (RunPod job webhook; the token is checked there)
  GET  /files/<key>?expires=..&sig=..       -> a KYC image of OBS_LOCAL_DIR, only with a valid, unexpired signature
                                               (LocalDiskStorage signs HMAC-SHA256(key + "\\n" + expires))
"""
import hashlib
import hmac
import http.client
import mimetypes
import os
import re
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
KEY_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,1000}$")


def load_env(path):
    env = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


ENV = load_env(os.environ.get("ENV_FILE", os.path.join(HERE, "..", "local.env")))
FILES_DIR = os.path.realpath(ENV["OBS_LOCAL_DIR"])
SIGNING_KEY = ENV["OBS_LOCAL_SIGNING_KEY"].encode()
KYC = ("127.0.0.1", int(os.environ.get("KYC_PORT", "8111")))
PORT = int(os.environ.get("PUBLIC_PROXY_PORT", "8199"))


class Handler(BaseHTTPRequestHandler):
    server_version = "bingo-local"
    sys_version = ""

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path != "/callback/runpod/kyc":
            return self._plain(404, "not found")
        length = int(self.headers.get("Content-Length") or 0)
        if length > 1_000_000:
            return self._plain(413, "too large")
        body = self.rfile.read(length) if length else b""
        conn = http.client.HTTPConnection(*KYC, timeout=15)
        try:
            conn.request("POST", self.path, body=body,
                         headers={"Content-Type": self.headers.get("Content-Type", "application/json")})
            upstream = conn.getresponse()
            data = upstream.read()
        except OSError:
            return self._plain(502, "bingo-kyc not reachable")
        finally:
            conn.close()
        self.send_response(upstream.status)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        if not url.path.startswith("/files/"):
            return self._plain(404, "not found")
        key = urllib.parse.unquote(url.path[len("/files/"):])
        query = urllib.parse.parse_qs(url.query)
        expires = (query.get("expires") or [""])[0]
        sig = (query.get("sig") or [""])[0]
        if not KEY_PATTERN.match(key) or ".." in key or not expires.isdigit():
            return self._plain(404, "not found")
        expected = hmac.new(SIGNING_KEY, f"{key}\n{expires}".encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, sig) or int(expires) < time.time():
            return self._plain(403, "invalid or expired signature")
        path = os.path.realpath(os.path.join(FILES_DIR, key))
        if not path.startswith(FILES_DIR + os.sep) or not os.path.isfile(path):
            return self._plain(404, "not found")
        with open(path, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(path)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "private, no-store")
        self.end_headers()
        self.wfile.write(data)

    def _plain(self, status, text):
        data = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        # never log query strings (webhook token, signatures)
        print("%s %s" % (self.command, urllib.parse.urlsplit(self.path).path), flush=True)


if __name__ == "__main__":
    print(f"public proxy on 127.0.0.1:{PORT} (files: {FILES_DIR}, kyc: {KYC[0]}:{KYC[1]})", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
