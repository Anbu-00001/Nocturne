"""A small ActivityWatch REST client (aw-server-rust, http://127.0.0.1:5600/api/0). Standard library only."""

from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Any

SERVER = "http://127.0.0.1:5600"


def parse_ts(text: str) -> int:
    """ActivityWatch's RFC 3339 timestamps (nanoseconds, Z or an offset) to epoch milliseconds."""
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)", text)
    if not m:
        raise ValueError(f"unreadable timestamp {text!r}")
    base, fraction, zone = m.groups()
    seconds = datetime.fromisoformat(base + ("+00:00" if zone == "Z" else zone)).timestamp()
    micros = int((fraction or "0")[:6].ljust(6, "0"))
    return int(seconds) * 1000 + micros // 1000


def iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def now_ms() -> int:
    return int(time.time() * 1000)


def event_interval(event: dict) -> tuple[int, int]:
    start = parse_ts(event["timestamp"])
    return start, start + round(float(event["duration"]) * 1000)


class ActivityWatch:
    """
    The calls Nocturne needs. The server cuts events at the edges of a queried range (seen on aw-server-rust 0.13.2: an
    event from 10:00 to 11:00 queried from 10:30 comes back as 10:30 to 11:00), so callers query wider than they keep.
    """

    def __init__(self, base: str = SERVER):
        self.base = base.rstrip("/") + "/api/0"

    def _call(self, method: str, path: str, body: Any = None, query: dict | None = None) -> Any:
        url = self.base + path + ("?" + urllib.parse.urlencode(query) if query else "")
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
        return json.loads(raw) if raw else None

    def buckets(self) -> dict:
        return self._call("GET", "/buckets/")

    def create_bucket(self, bucket: str, kind: str, hostname: str, client: str = "nocturne") -> None:
        try:
            self._call("POST", f"/buckets/{bucket}", {"client": client, "type": kind, "hostname": hostname})
        except urllib.error.HTTPError as e:
            if e.code != 304:  # already exists
                raise

    def delete_bucket(self, bucket: str) -> None:
        try:
            self._call("DELETE", f"/buckets/{bucket}", query={"force": 1})
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise

    def events(self, bucket: str, start: int, end: int) -> list[dict]:
        """Events overlapping [start, end], cut to it by the server; empty when the bucket does not exist."""
        try:
            return self._call("GET", f"/buckets/{bucket}/events", query={"start": iso(start), "end": iso(end), "limit": -1})
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return []
            raise

    def info(self) -> dict:
        return self._call("GET", "/info")

    def latest(self, bucket: str) -> dict | None:
        """The bucket's most recent event, or None when it has none or does not exist."""
        try:
            events = self._call("GET", f"/buckets/{bucket}/events", query={"limit": 1})
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise
        return events[0] if events else None

    def insert(self, bucket: str, events: list[dict]) -> None:
        for i in range(0, len(events), 1000):
            self._call("POST", f"/buckets/{bucket}/events", events[i:i + 1000])

    def heartbeat(self, bucket: str, event: dict, pulsetime: float) -> None:
        """Extends the bucket's last event when [event] has the same data and starts within [pulsetime] s of its end."""
        self._call("POST", f"/buckets/{bucket}/heartbeat", event, query={"pulsetime": pulsetime})
