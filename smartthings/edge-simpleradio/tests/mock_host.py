#!/usr/bin/env python3
"""Mock Simple Radio host implementing smartthings/LAN_API.md (v1) for tests.

Real endpoints:
  GET  /api/v1/info            {"id","name","model","appVersion","apiVersion":1,"pairingOpen":bool}
  POST /api/v1/pair            200 {"token"} while the pairing window is open, else 403 pairing_closed
  GET  /api/v1/state           (Bearer) state JSON
  POST /api/v1/command         (Bearer) {"command","value"} -> {"ok":true} | 400 {"ok":false,"message"}
  GET  /api/v1/events          (Bearer) SSE: current state at once, "event: state" on change, ": ping"
  GET  /art/station/<id>.png   tiny PNG

Test-control endpoints (not part of the contract):
  POST /_mock/pairing   {"open": true|false}   open/close the pairing window
  POST /_mock/state     {partial state}         merge into state and push an SSE event
  POST /_mock/config    {"sse_chunked": bool, "ping_interval": seconds}
  POST /_mock/config    {"events_status": 404}  refuse /api/v1/events with that status (200 = normal)
  POST /_mock/revoke    {}                      forget all tokens (next request -> 401)
  POST /_mock/drop_streams {}                   end every open SSE stream
  GET  /_mock/commands                          list of commands received so far
  GET  /_mock/stats                             {"pairAttempts","streams","tokens","commands"}

Usage: mock_host.py [--host 127.0.0.1] [--port 8765|0] [--port-file F] [--pairing-open] [--quiet]
Stdlib only.
"""
import argparse
import json
import queue
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PNG_1PX = bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010806000000"
    "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082"
)


class HostState:
    def __init__(self, base_url, pairing_open=False):
        self.lock = threading.Lock()
        self.tokens = set()
        self.pairing_until = time.time() + 180 if pairing_open else 0.0
        self.commands = []
        self.subscribers = set()
        self.sse_chunked = False
        self.ping_interval = 25.0
        self.events_status = 200  # set e.g. 404 to refuse the SSE endpoint
        self.pair_attempts = 0
        art = base_url + "/art/station/{}.png"
        self.presets = [
            {"id": "1", "name": "KBS Cool FM", "imageUrl": art.format(1)},
            {"id": "2", "name": "MBC FM4U", "imageUrl": art.format(2)},
            {"id": "3", "name": "SBS Power FM", "imageUrl": art.format(3)},
        ]
        self.state = {
            "power": "off",
            "playback": "stopped",
            "mediaId": "1",
            "title": "KBS Cool FM",
            "artist": "",
            "albumArtUrl": art.format(1),
            "volume": 40,
            "muted": False,
            "presets": self.presets,
            "updatedAtMs": int(time.time() * 1000),
        }

    # -- helpers ---------------------------------------------------------
    def pairing_open(self):
        return time.time() < self.pairing_until

    def snapshot(self):
        with self.lock:
            return json.loads(json.dumps(self.state))

    def publish(self):
        data = json.dumps(self.snapshot(), ensure_ascii=False)
        with self.lock:
            subs = list(self.subscribers)
        for q in subs:
            q.put(data)

    def merge(self, patch):
        with self.lock:
            self.state.update(patch)
            self.state["updatedAtMs"] = int(time.time() * 1000)
        self.publish()

    def select_preset(self, preset_id):
        for p in self.presets:
            if p["id"] == preset_id:
                return {"mediaId": p["id"], "title": p["name"], "albumArtUrl": p["imageUrl"],
                        "power": "on", "playback": "playing"}
        return None

    def apply_command(self, cmd, value):
        """Returns (http_status, body)."""
        s = self.state
        patch = None
        if cmd == "on":
            patch = {"power": "on", "playback": "playing"}
        elif cmd in ("off", "stop"):
            patch = {"power": "off", "playback": "stopped"}
        elif cmd == "play":
            patch = {"power": "on", "playback": "playing"}
        elif cmd == "pause":
            patch = {"power": "off", "playback": "paused"}
        elif cmd in ("next", "previous"):
            ids = [p["id"] for p in self.presets]
            i = ids.index(s["mediaId"]) if s["mediaId"] in ids else 0
            i = (i + (1 if cmd == "next" else -1)) % len(ids)
            patch = self.select_preset(ids[i])
        elif cmd == "setVolume":
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0 <= value <= 100:
                return 400, {"ok": False, "message": "value must be 0..100"}
            patch = {"volume": int(value)}
        elif cmd == "volumeUp":
            patch = {"volume": min(100, s["volume"] + 5)}
        elif cmd == "volumeDown":
            patch = {"volume": max(0, s["volume"] - 5)}
        elif cmd == "mute":
            patch = {"muted": True}
        elif cmd == "unmute":
            patch = {"muted": False}
        elif cmd == "playPreset":
            patch = self.select_preset(value if isinstance(value, str) else str(value))
            if patch is None:
                return 400, {"ok": False, "message": "unknown preset"}
        else:
            return 400, {"ok": False, "message": "unknown command"}
        self.merge(patch)
        return 200, {"ok": True}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "SimpleRadioMock/1"
    host: HostState = None  # set on the class by main()
    quiet = False

    def log_message(self, fmt, *args):
        if not self.quiet:
            sys.stderr.write("[mock] " + (fmt % args) + "\n")

    # -- io helpers --------------------------------------------------------
    def read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            return None

    def send_json(self, status, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer ") and auth[7:] in self.host.tokens:
            return True
        self.send_json(401, {"error": "unauthorized"})
        return False

    # -- routes ------------------------------------------------------------
    def do_GET(self):
        h = self.host
        if self.path == "/api/v1/info":
            self.send_json(200, {"id": "mock-host-id", "name": "Mock Tablet", "model": "MockModel",
                                 "appVersion": "0.0.0-mock", "apiVersion": 1,
                                 "pairingOpen": h.pairing_open()})
        elif self.path == "/api/v1/state":
            if self.authorized():
                self.send_json(200, h.snapshot())
        elif self.path == "/api/v1/events":
            if self.authorized():
                if h.events_status != 200:
                    self.send_json(h.events_status, {"error": "events_disabled"})
                else:
                    self.stream_events()
        elif self.path.startswith("/art/station/") and self.path.endswith(".png"):
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(PNG_1PX)))
            self.end_headers()
            self.wfile.write(PNG_1PX)
        elif self.path == "/_mock/commands":
            self.send_json(200, h.commands)
        elif self.path == "/_mock/stats":
            with h.lock:
                streams = len(h.subscribers)
            self.send_json(200, {"pairAttempts": h.pair_attempts, "streams": streams,
                                 "tokens": len(h.tokens), "commands": len(h.commands)})
        else:
            self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        h = self.host
        body = self.read_json()
        if body is None:
            self.send_json(400, {"ok": False, "message": "bad json"})
            return
        if self.path == "/api/v1/pair":
            h.pair_attempts += 1
            if not h.pairing_open():
                self.send_json(403, {"error": "pairing_closed"})
                return
            token = secrets.token_urlsafe(24)
            h.tokens.add(token)
            self.send_json(200, {"token": token})
        elif self.path == "/api/v1/command":
            if not self.authorized():
                return
            cmd = body.get("command")
            h.commands.append({"command": cmd, "value": body.get("value"), "hasValue": "value" in body})
            status, resp = h.apply_command(cmd, body.get("value"))
            self.send_json(status, resp)
        elif self.path == "/_mock/pairing":
            h.pairing_until = time.time() + 180 if body.get("open", True) else 0.0
            self.send_json(200, {"pairingOpen": h.pairing_open()})
        elif self.path == "/_mock/state":
            h.merge(body)
            self.send_json(200, {"ok": True})
        elif self.path == "/_mock/config":
            if "sse_chunked" in body:
                h.sse_chunked = bool(body["sse_chunked"])
            if "ping_interval" in body:
                h.ping_interval = float(body["ping_interval"])
            if "events_status" in body:
                h.events_status = int(body["events_status"])
            self.send_json(200, {"sse_chunked": h.sse_chunked, "ping_interval": h.ping_interval,
                                 "events_status": h.events_status})
        elif self.path == "/_mock/drop_streams":
            with h.lock:
                subs = list(h.subscribers)
            for q in subs:
                q.put(None)
            self.send_json(200, {"dropped": len(subs)})
        elif self.path == "/_mock/revoke":
            h.tokens.clear()
            self.send_json(200, {"ok": True})
        else:
            self.send_json(404, {"error": "not_found"})

    # -- SSE -----------------------------------------------------------------
    def stream_events(self):
        h = self.host
        chunked = h.sse_chunked
        q = queue.Queue()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        if chunked:
            self.send_header("Transfer-Encoding", "chunked")
        else:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()

        def write(text):
            data = text.encode("utf-8")
            if chunked:
                data = b"%x\r\n" % len(data) + data + b"\r\n"
            self.wfile.write(data)
            self.wfile.flush()

        with h.lock:
            h.subscribers.add(q)
        try:
            write("event: state\ndata: " + json.dumps(h.snapshot(), ensure_ascii=False) + "\n\n")
            while True:
                try:
                    data = q.get(timeout=h.ping_interval)
                    if data is None:  # /_mock/drop_streams
                        if chunked:
                            self.wfile.write(b"0\r\n\r\n")
                            self.wfile.flush()
                        self.close_connection = True
                        return
                    write("event: state\ndata: " + data + "\n\n")
                except queue.Empty:
                    write(": ping\n\n")
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with h.lock:
                h.subscribers.discard(q)


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # clients (the driver under test) drop SSE connections on purpose
        if isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError)):
            return
        super().handle_error(request, client_address)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--port-file")
    ap.add_argument("--pairing-open", action="store_true")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    server = QuietServer((args.host, args.port), Handler)
    port = server.server_address[1]
    Handler.host = HostState("http://%s:%d" % (args.host, port), pairing_open=args.pairing_open)
    Handler.quiet = args.quiet
    if args.port_file:
        with open(args.port_file, "w") as f:
            f.write(str(port))
    if not args.quiet:
        print("mock Simple Radio host on http://%s:%d" % (args.host, port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
