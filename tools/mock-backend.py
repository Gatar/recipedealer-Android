"""Minimal stand-in for the example REST API (final-app.md section 8), for manual end-to-end tests only - not the backend.

usage: TOKEN=dobry-token python3 tools/mock-backend.py <dir-for-received-bodies>
       adb reverse tcp:8765 tcp:8765   (app setting: http://localhost:8765; debug builds allow cleartext)
"""
import json, os, re, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
TOKEN = os.environ.get("TOKEN", "dobry-token")
STORE = sys.argv[1]
seen = {}
class H(BaseHTTPRequestHandler):
    def reply(self, code, obj=None):
        body = json.dumps(obj or {}).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def authed(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.reply(401, {"error": "unauthorized"}); return False
        return True
    def do_GET(self):
        if not self.authed(): return
        if self.path == "/v1/health": return self.reply(200, {"status": "ok"})
        m = re.match(r"^/v1/captures/([0-9a-f-]+)$", self.path)
        if m and m.group(1) in seen:
            return self.reply(200, {"captureId": m.group(1), "status": "done", "recipes": [{"recipeId": "r1", "title": seen[m.group(1)] + " (backend)"}], "message": None})
        self.reply(404, {"error": "not_found"})
    def do_PUT(self):
        if not self.authed(): return
        m = re.match(r"^/v1/captures/([0-9a-f-]+)$", self.path)
        body = self.rfile.read(int(self.headers["Content-Length"]))
        cid = m.group(1)
        with open(os.path.join(STORE, cid + ".multipart"), "wb") as f: f.write(body)
        boundary = self.headers["Content-Type"].split("boundary=")[1].encode()
        parts = [p for p in body.split(b"--" + boundary) if b"Content-Disposition" in p]
        names = [re.search(rb'name="([^"]+)"', p).group(1).decode() for p in parts]
        cap = json.loads(parts[0].split(b"\r\n\r\n", 1)[1].rsplit(b"\r\n", 1)[0])
        existed = cid in seen
        seen[cid] = cap["recipe"].get("title") or "?"
        print("PUT", cid, names, "existed" if existed else "new", flush=True)
        self.reply(200 if existed else 201, {"captureId": cid, "status": "accepted"})
    def log_message(self, *a): pass
ThreadingHTTPServer(("0.0.0.0", 8765), H).serve_forever()
