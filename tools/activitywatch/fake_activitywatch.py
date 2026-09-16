"""Enough of aw-server-rust's REST API for the tests, with its behaviour where it matters: events cut to a query's range,
heartbeats merged within their pulsetime."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from awclient import ActivityWatch, event_interval, iso, parse_ts


class FakeActivityWatch(BaseHTTPRequestHandler):
    """Buckets, events and heartbeats, held in memory."""
    buckets: dict[str, dict] = {}

    def log_message(self, format, *args):
        pass

    def reply(self, code: int, body=None):
        raw = b"" if body is None else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def body(self):
        return json.loads(self.rfile.read(int(self.headers["Content-Length"])))

    def route(self):
        url = urlparse(self.path)
        parts = url.path.split("/")[3:]  # after /api/0
        return parts, parse_qs(url.query)

    def do_GET(self):
        parts, query = self.route()
        if parts == ["buckets", ""]:
            return self.reply(200, {k: v["meta"] for k, v in self.buckets.items()})
        bucket = self.buckets.get(parts[1])
        if bucket is None:
            return self.reply(404)
        start, end = parse_ts(query["start"][0]), parse_ts(query["end"][0])
        cut = []
        for e in bucket["events"]:
            a, b = event_interval(e)
            if a <= end and b >= start:
                a, b = max(a, start), min(b, end)
                cut.append({**e, "timestamp": iso(a), "duration": (b - a) / 1000})
        return self.reply(200, cut)

    def do_POST(self):
        parts, query = self.route()
        if len(parts) == 2:
            if parts[1] in self.buckets:
                return self.reply(304)
            self.buckets[parts[1]] = {"meta": self.body(), "events": []}
            return self.reply(200)
        bucket = self.buckets[parts[1]]
        if parts[2] == "events":
            bucket["events"].extend(self.body())
            return self.reply(200)
        beat = self.body()
        events = bucket["events"]
        pulse = float(query["pulsetime"][0]) * 1000
        if events and events[-1]["data"] == beat["data"]:
            last = events[-1]
            start, end = event_interval(last)
            beat_start, beat_end = event_interval(beat)
            if start <= beat_start <= end + pulse:
                last["duration"] = (max(end, beat_end) - start) / 1000
                return self.reply(200, last)
        events.append(beat)
        return self.reply(200, beat)

    def do_DELETE(self):
        parts, _ = self.route()
        return self.reply(200) if self.buckets.pop(parts[1], None) is not None else self.reply(404)



class FakeServer:
    """A running fake on a free port; use as a context manager."""

    def __enter__(self) -> ActivityWatch:
        FakeActivityWatch.buckets = {}
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeActivityWatch)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return ActivityWatch(f"http://127.0.0.1:{self.server.server_address[1]}")

    def __exit__(self, *exc) -> None:
        self.server.shutdown()
        self.server.server_close()
