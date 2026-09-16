#!/usr/bin/env python3
"""Nocturne and ActivityWatch on the laptop (spec §9 Phase 4). Standard library only.

ActivityWatch records when someone is at the laptop. This tool adds what it does not record, the screen's backlight and
night filter, and moves data between the laptop and the phone over adb:

  watch         record backlight and Night Light into nocturne-display_<host> every minute; with --afk, presence too
                (laptop_state.py says when that is needed)
  laptop-file   write the laptop use Nocturne imports (not-afk spans with their display state) to a file or stdout
  send          laptop-file, straight into the phone over adb, then wait for the import's result
  phone-to-aw   read the phone's sessions and nights over adb into ActivityWatch buckets, replacing what they held
  sync          send, then phone-to-aw; with --if-connected, quietly nothing when no phone is on adb
  status        whether the server, both buckets and the phone are recording and in reach

The phone side is Nocturne's LaptopBridgeProvider; `content write/read/query` on it need adb's shell. Nothing here opens a
network port or sends anything beyond localhost and USB.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from awclient import SERVER, ActivityWatch, event_interval, iso, now_ms, parse_ts
from laptop_state import afk_bucket, display_bucket, host_name, panel_size_mm, watch

HEADER = "nocturne-laptop,1"
AUTHORITY = "io.github.anbu00001.nocturne.laptop"
DAY_MS = 86_400_000
HOST_RE = re.compile(r"[A-Za-z0-9._-]{1,64}")


# ------------------------------------------------------------------------------------------------------- laptop spans

@dataclass(frozen=True)
class Display:
    host: str
    width_mm: int
    height_mm: int
    min_nits: float
    peak_nits: float


@dataclass(frozen=True)
class Span:
    start: int
    end: int
    backlight: float | None
    night_light: bool | None


def merge(intervals: list[tuple[int, int]]) -> list[tuple[int, int]]:
    """Sorted, disjoint intervals; touching ones join."""
    merged: list[tuple[int, int]] = []
    for start, end in sorted(intervals):
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def spans_from_events(afk: list[dict], display: list[dict], reach_ms: int) -> list[Span]:
    """
    Not-afk stretches, cut wherever the display state changed. A display event holds from its first heartbeat to its last
    plus [reach_ms], the watcher's interval, and never past the next event; outside every event the state is unknown.
    """
    active = merge([event_interval(e) for e in afk if e.get("data", {}).get("status") == "not-afk"])
    states = []
    for e in sorted(display, key=lambda e: parse_ts(e["timestamp"])):
        start, end = event_interval(e)
        data = e.get("data", {})
        backlight = data.get("backlight")
        night = data.get("nightLight")
        states.append([start, end + reach_ms, None if backlight is None else round(float(backlight), 3), night if isinstance(night, bool) else None])
    for current, following in zip(states, states[1:]):
        current[1] = min(current[1], following[0])

    def state_at(t: int) -> tuple[float | None, bool | None]:
        for start, end, backlight, night in reversed(states):
            if start <= t < end:
                return backlight, night
            if start <= t:
                break
        return None, None

    spans: list[Span] = []
    for start, end in active:
        cuts = sorted({start, *(p for s in states for p in (s[0], s[1]) if start < p < end)})
        pieces: list[Span] = []
        for i, a in enumerate(cuts):
            piece = Span(a, cuts[i + 1] if i + 1 < len(cuts) else end, *state_at(a))
            if pieces and (pieces[-1].backlight, pieces[-1].night_light) == (piece.backlight, piece.night_light):
                pieces[-1] = Span(pieces[-1].start, piece.end, piece.backlight, piece.night_light)
            else:
                pieces.append(piece)
        spans.extend(pieces)
    return spans


def laptop_file(display: Display, cover_from: int, cover_to: int, spans: list[Span]) -> str:
    """The format core-model's LaptopFileFormat reads; spans starting outside the coverage are left to other files."""
    if not HOST_RE.fullmatch(display.host):
        raise ValueError(f"host {display.host!r} does not fit the file format")

    def number(x: float) -> str:
        return repr(float(x))

    lines = [
        HEADER,
        f"display,{display.host},{display.width_mm},{display.height_mm},{number(display.min_nits)},{number(display.peak_nits)}",
        f"coverage,{display.host},{cover_from},{cover_to}",
    ]
    for s in spans:
        if not cover_from <= s.start < cover_to:
            continue
        backlight = "" if s.backlight is None else number(s.backlight)
        night = "" if s.night_light is None else ("1" if s.night_light else "0")
        lines.append(f"span,{display.host},{s.start},{s.end},{backlight},{night}")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------------------------------------- the phone

def phone_connected(serial: str | None) -> bool:
    try:
        result = subprocess.run(["adb", *(["-s", serial] if serial else []), "get-state"], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0 and result.stdout.strip() == "device"


def adb(*args: str, stdin: bytes | None = None, serial: str | None = None) -> str:
    command = ["adb", *(["-s", serial] if serial else []), "shell", *args]
    result = subprocess.run(command, input=stdin, capture_output=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout.decode()


def parse_import_status(out: str) -> dict[str, str | None]:
    """`content query` prints `Row: 0 sequence=3, outcome=ok, ..., error=NULL`; error, which may hold commas, is last."""
    out = out.strip()
    if not out.startswith("Row:"):
        raise RuntimeError(f"no import status from the phone: {out!r}")
    body = out.split(" ", 2)[2]
    head, _, error = body.partition(", error=")
    status: dict[str, str | None] = {}
    for field in head.split(", "):
        key, _, value = field.partition("=")
        status[key] = value
    status["error"] = None if error == "NULL" else error
    return status


def import_status(serial: str | None) -> dict[str, str | None]:
    return parse_import_status(adb("content", "query", "--uri", f"content://{AUTHORITY}/import", serial=serial))


def send(text: str, serial: str | None, wait_s: float = 120) -> dict[str, str | None]:
    before = int(import_status(serial)["sequence"] or 0)
    adb("content", "write", "--uri", f"content://{AUTHORITY}/import", stdin=text.encode(), serial=serial)
    deadline = time.monotonic() + wait_s
    while time.monotonic() < deadline:
        status = import_status(serial)
        if int(status["sequence"] or 0) > before and status["outcome"] != "running":
            return status
        time.sleep(1)
    raise TimeoutError("the phone did not finish the import in time")


def rows(csv_text: str) -> list[dict]:
    return list(csv.DictReader(io.StringIO(csv_text)))


def session_events(csv_text: str) -> list[dict]:
    events = []
    for r in rows(csv_text):
        start, end = int(r["startTs"]), int(r["endTs"])
        events.append({
            "timestamp": iso(start),
            "duration": (end - start) / 1000,
            "data": {
                "kind": r["kind"],
                "unlocked": r["unlocked"] == "true",
                "trigger": r["trigger"],
                "glance": r["countsAsGlance"] == "true",
                "app": r["dominantPackage"] or None,
            },
        })
    return events


def sleep_events(csv_text: str) -> list[dict]:
    """Nights with a sleep; a sleepless night has no interval to show."""
    events = []
    for r in rows(csv_text):
        if r["noSleep"] == "true" or not r["onsetTs"] or not r["wakeTs"]:
            continue
        onset, wake = int(r["onsetTs"]), int(r["wakeTs"])
        events.append({
            "timestamp": iso(onset),
            "duration": (wake - onset) / 1000,
            "data": {"night": r["dateOfNight"], "source": r["source"], "confidence": round(float(r["confidence"]), 3)},
        })
    return events


def phone_to_aw(aw: ActivityWatch, serial: str | None) -> dict:
    """Derived rows change with every recompute, so each bucket is rebuilt whole from the phone's full history."""
    device = re.sub(r"[^A-Za-z0-9._-]", "-", adb("getprop", "ro.product.model", serial=serial).strip()) or "phone"
    sessions = adb("content", "read", "--uri", f"content://{AUTHORITY}/sessions?from=0", serial=serial)
    nights = adb("content", "read", "--uri", f"content://{AUTHORITY}/nights?from=2000-01-01", serial=serial)
    counts = {}
    for bucket, kind, events in (
        (f"nocturne-sessions_{device}", "nocturne.session", session_events(sessions)),
        (f"nocturne-sleep_{device}", "nocturne.sleep", sleep_events(nights)),
    ):
        aw.delete_bucket(bucket)
        aw.create_bucket(bucket, kind, hostname=device)
        aw.insert(bucket, events)
        counts[bucket] = len(events)
    return counts


# ----------------------------------------------------------------------------------------------------------- commands

def age(ms: int) -> str:
    minutes = max(0, now_ms() - ms) // 60_000
    return f"{minutes} min ago" if minutes < 120 else f"{minutes // 60} h ago"


def status(aw: ActivityWatch, host: str, serial: str | None) -> list[str]:
    """One line per part of the chain, each saying what it last did; nothing here changes anything."""
    lines = []
    try:
        lines.append(f"server: {aw.info().get('version')} at {aw.base}")
    except OSError as e:
        return [f"server: not reachable at {aw.base} ({e})"]
    for bucket in (afk_bucket(host), display_bucket(host)):
        last = aw.latest(bucket)
        if last is None:
            lines.append(f"{bucket}: no events")
        else:
            _, end = event_interval(last)
            lines.append(f"{bucket}: last {json.dumps(last['data'], sort_keys=True)} until {age(end)}")
    if not phone_connected(serial):
        lines.append("phone: not connected over adb")
    else:
        try:
            st = import_status(serial)
            finished = st.get("finishedAt")
            when = age(int(finished)) if finished and finished != "NULL" else "never"
            lines.append(f"phone: last import {st['outcome']} {when}, {st['spans']} spans, {st['nights']} nights re-derived"
                         + (f", error: {st['error']}" if st["error"] else ""))
        except RuntimeError as e:
            lines.append(f"phone: connected, Nocturne's bridge did not answer ({e})")
    return lines


def build_laptop_file(aw: ActivityWatch, args) -> str:
    """
    The server cuts events at a query's edges, so events are read from a day before the coverage: a stretch that began
    before it then keeps its real start and is left to the file that covered that start, instead of arriving cut short.
    """
    host = args.host or host_name()
    size = (args.width_mm, args.height_mm) if args.width_mm and args.height_mm else panel_size_mm()
    if size is None:
        raise SystemExit("no built-in panel found; pass --width-mm and --height-mm")
    display = Display(host, size[0], size[1], args.min_nits, args.peak_nits)
    to = now_ms()
    cover_from = to - args.days * DAY_MS
    afk = aw.events(afk_bucket(host), cover_from - DAY_MS, to)
    shown = aw.events(display_bucket(host), cover_from - 2 * DAY_MS, to)
    return laptop_file(display, cover_from, to, spans_from_events(afk, shown, reach_ms=args.reach_s * 1000))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", default=SERVER, help="ActivityWatch server (default %(default)s)")
    parser.add_argument("--serial", help="adb device serial, when more than one is connected")
    sub = parser.add_subparsers(dest="command", required=True)

    watcher = sub.add_parser("watch")
    watcher.add_argument("--interval", type=int, default=60, help="seconds between display readings (default %(default)s)")
    watcher.add_argument("--afk", action="store_true", help="also record presence; only where no ActivityWatch AFK watcher runs")
    watcher.add_argument("--timeout", type=int, default=180, help="seconds without input before afk (default %(default)s)")

    for name in ("laptop-file", "send", "sync"):
        p = sub.add_parser(name)
        p.add_argument("--days", type=int, default=7, help="laptop use starting in the last N days (default %(default)s)")
        p.add_argument("--host", help="ActivityWatch host name (default: this machine's)")
        p.add_argument("--peak-nits", type=float, default=250.0, help="panel luminance at full backlight (default %(default)s)")
        p.add_argument("--min-nits", type=float, default=2.0, help="panel luminance at the lowest backlight (default %(default)s)")
        p.add_argument("--width-mm", type=int)
        p.add_argument("--height-mm", type=int)
        p.add_argument("--reach-s", type=int, default=90, help="how long a display reading holds after its last heartbeat")
        if name == "laptop-file":
            p.add_argument("--out", help="file to write (default: stdout)")
        else:
            p.add_argument("--if-connected", action="store_true", help="exit quietly when no phone is on adb (for a timer)")

    sub.add_parser("phone-to-aw")
    sub.add_parser("status")
    args = parser.parse_args(argv)
    aw = ActivityWatch(args.server)

    if args.command == "watch":
        watch(aw, host_name(), display_every_s=args.interval, afk=args.afk, timeout_s=args.timeout)
    elif args.command == "laptop-file":
        text = build_laptop_file(aw, args)
        if args.out:
            Path(args.out).write_text(text)
        else:
            sys.stdout.write(text)
    elif args.command in ("send", "sync"):
        if args.if_connected and not phone_connected(args.serial):
            return
        text = build_laptop_file(aw, args)
        spans = text.count("\nspan,")
        result = send(text, args.serial)
        print(f"sent {spans} spans: {result['outcome']}, {result['added']} new or changed, {result['removed']} gone, "
              f"{result['nights']} nights re-derived" + (f", error: {result['error']}" if result["error"] else ""))
        if result["outcome"] != "ok":
            raise SystemExit(1)
        if args.command == "sync":
            for bucket, count in phone_to_aw(aw, args.serial).items():
                print(f"{bucket}: {count} events")
    elif args.command == "phone-to-aw":
        for bucket, count in phone_to_aw(aw, args.serial).items():
            print(f"{bucket}: {count} events")
    elif args.command == "status":
        print("\n".join(status(aw, host_name(), args.serial)))


if __name__ == "__main__":
    main()
