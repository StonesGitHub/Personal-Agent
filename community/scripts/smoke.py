#!/usr/bin/env python3
"""Read-only deployment smoke test. Tokens only from env/private .env, never CLI args."""
import argparse
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
from urllib.parse import urlsplit

p = argparse.ArgumentParser()
p.add_argument("base", help="https://your-domain or http://127.0.0.1:8090")
p.add_argument("--web", action="store_true", help="Also fetch example.com and perform one public search")
a = p.parse_args()
u = urlsplit(a.base)
if u.scheme != "https" and not (u.scheme == "http" and u.hostname in ("127.0.0.1", "localhost")):
    p.error("Use HTTPS, except for loopback smoke testing.")
token = os.environ.get("MOZHI_WEB_TOKEN", "")
if not token:
    env = Path(__file__).resolve().parent.parent / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            if line.startswith("MOZHI_WEB_TOKENS="):
                token = line.partition("=")[2].split(",")[0].strip()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None
opener = urllib.request.build_opener(NoRedirect)
def call(path, body=None, authorized=False):
    request = urllib.request.Request(a.base.rstrip("/") + path, data=None if body is None else json.dumps(body).encode(), headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if authorized else {})})
    with opener.open(request, timeout=50) as response:
        return json.load(response)
assert call("/health")["ok"]
assert isinstance(call("/v1/posts")["items"], list)
if not token:
    raise SystemExit("Health/community read passed; set MOZHI_WEB_TOKEN to check gateway authentication.")
try:
    call("/v1/web/status")
    raise SystemExit("FAIL: web gateway accepted an unauthenticated request")
except urllib.error.HTTPError as e:
    assert e.code == 401
assert call("/v1/web/status", authorized=True)["ok"]
if a.web:
    assert call("/v1/web/read", {"url": "https://example.com/"}, True)["text"]
    assert call("/v1/web/search", {"query": "Android developer documentation"}, True)["results"]
print("PASS: health, community read, gateway authentication" + (", live read/search" if a.web else ""))
