#!/usr/bin/env python3
"""Nocturne and ActivityWatch on the laptop (spec §9 Phase 4). Standard library only.

ActivityWatch records when someone is at the laptop. This tool adds what it does not record, the screen's backlight and
night filter, and moves data between the laptop and the phone, over adb or (Phase 5) over the home Wi-Fi:

  watch         record backlight and Night Light into nocturne-display_<host> every minute; with --afk, presence too
                (laptop_state.py says when that is needed)
  laptop-file   write the laptop use Nocturne imports (not-afk spans with their display state) to a file or stdout
  send          laptop-file, straight into the phone over adb, then wait for the import's result
  phone-to-aw   read the phone's sessions and nights over adb into ActivityWatch buckets, replacing what they held
  sync          send, then phone-to-aw; with --if-connected, quietly nothing when no phone is on adb
  status        whether the server, both buckets, the top-bar menu and the phone are recording, used and in reach
  wifi-setup    over USB, once per phone boot: let `sync --wifi` reach the phone over Wi-Fi (adb on port 5555)
  serve         the laptop's end of Wi-Fi sync: answer the phone's exchanges while the laptop is on (lansync.py)
  pair          over USB, once: give the phone this laptop's address, certificate fingerprint and secret

The phone side is Nocturne's LaptopBridgeProvider; `content write/read/query` on it need adb's shell. `serve` is how the
phone reaches the laptop without adb: the phone starts each exchange, so it opens no port. Nothing here sends anything
beyond localhost, USB and, only after wifi-setup, adb on the home network; `serve` answers only the paired phone, on
private addresses.
"""

from __future__ import annotations

import argparse
from datetime import date
import csv
import io
import json
import re
import socket
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import lansync
from awclient import SERVER, ActivityWatch, event_interval, iso, now_ms, parse_ts
from laptop_state import afk_bucket, display_bucket, host_name, panel_size_mm, watch
from engagement import summary as engagement_summary
from tonight import PhoneSummary, state_dir

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

def adb_host(*args: str, serial: str | None = None, timeout: float = 30) -> str:
    """An adb command run on the laptop side (connect, tcpip), not in the phone's shell."""
    command = ["adb", *(["-s", serial] if serial else []), *args]
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed: {result.stderr.strip()}")
    return result.stdout


def wlan_address(ip_output: str) -> str | None:
    """The phone's Wi-Fi IPv4 address from `ip -f inet addr show wlan0`."""
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/", ip_output)
    return m.group(1) if m else None


def wifi_file() -> Path:
    return state_dir() / "phone-wifi.json"


def wifi_setup(serial: str | None) -> str:
    """
    Over USB, once per phone boot: adb over TCP on port 5555 (Android 15 and earlier; Android 17's ADB Wi-Fi 2.0 reconnects
    paired phones by itself). Only laptops the phone has already authorised can connect, but the port is open to the whole
    network, so use it on a home network you trust; `adb usb` or a reboot closes it.
    """
    address = wlan_address(adb("ip", "-f", "inet", "addr", "show", "wlan0", serial=serial))
    if address is None:
        raise RuntimeError("the phone has no Wi-Fi address; connect it to Wi-Fi first")
    adb_host("tcpip", "5555", serial=serial)
    target = f"{address}:5555"
    wifi_file().parent.mkdir(parents=True, exist_ok=True)
    wifi_file().write_text(json.dumps({"address": target}))
    return target


def wifi_connect() -> str | None:
    """The remembered address as an adb serial when the phone answers there, else None."""
    try:
        target = json.loads(wifi_file().read_text())["address"]
    except (OSError, ValueError, KeyError):
        return None
    try:
        adb_host("connect", target, timeout=15)
    except (RuntimeError, subprocess.SubprocessError):
        return None
    return target if phone_connected(target) else None


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


def device_name(model: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "-", model.strip()) or "phone"


def save_summary(device: str, nights: str) -> None:
    """What the top-bar indicator shows between syncs: the evening window and the latest night."""
    summary = PhoneSummary.from_nights(rows(nights), synced_at=now_ms(), device=device)
    if summary:
        summary.keeping_window_of(PhoneSummary.load()).save()


def rebuild_buckets(aw: ActivityWatch, device: str, sessions: str, nights: str) -> dict:
    """Derived rows change with every recompute, so each bucket is rebuilt whole from the phone's full history."""
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


def phone_rows_to_aw(aw: ActivityWatch, device: str, sessions: str, nights: str) -> dict:
    save_summary(device, nights)
    return rebuild_buckets(aw, device, sessions, nights)


def phone_to_aw(aw: ActivityWatch, serial: str | None) -> dict:
    device = device_name(adb("getprop", "ro.product.model", serial=serial))
    sessions = adb("content", "read", "--uri", f"content://{AUTHORITY}/sessions?from=0", serial=serial)
    nights = adb("content", "read", "--uri", f"content://{AUTHORITY}/nights?from=2000-01-01", serial=serial)
    return phone_rows_to_aw(aw, device, sessions, nights)


# ---------------------------------------------------------------------------------------------------------- Wi-Fi sync

def laptop_address() -> str | None:
    """The address this laptop reaches its router by, as a hint for the phone (which also tries the names). Sends nothing."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            return probe.getsockname()[0]
    except OSError:
        return None


def pairing_text(identity: lansync.Identity, host: str, address: str | None, port: int) -> str:
    """What the phone's `content write .../pair` takes: where to look for this laptop, and what to trust there."""
    lines = ["nocturne-pair,1", f"name,{host}", f"host,{host}.local", f"host,{host}"]
    if address:
        lines.append(f"address,{address}")
    lines += [f"port,{port}", f"cert,{identity.fingerprint}", f"secret,{identity.secret}"]
    return "\n".join(lines) + "\n"


def parse_row(out: str, last: str) -> dict[str, str | None]:
    """`content query` prints `Row: 0 a=1, b=2, ..., <last>=...`; the last field, which may hold commas, is NULL when empty."""
    out = out.strip()
    if not out.startswith("Row:"):
        raise RuntimeError(f"no answer from the phone: {out!r}")
    head, _, tail = out.split(" ", 2)[2].partition(f", {last}=")
    row: dict[str, str | None] = {}
    for field in head.split(", "):
        key, _, value = field.partition("=")
        row[key] = value
    row[last] = None if tail == "NULL" else tail
    return row


def pair_row(serial: str | None) -> dict[str, str | None]:
    return parse_row(adb("content", "query", "--uri", f"content://{AUTHORITY}/pair", serial=serial), last="error")


def pair(args, wait_s: float = 45) -> list[str]:
    identity = lansync.load_identity()
    host = host_name()
    if not HOST_RE.fullmatch(host):
        raise SystemExit(f"host name {host!r} does not fit the pairing format")
    text = pairing_text(identity, host, laptop_address(), args.port)
    before = pair_row(args.serial)
    adb("content", "write", "--uri", f"content://{AUTHORITY}/pair", stdin=text.encode(), serial=args.serial)
    deadline = time.monotonic() + wait_s
    row = before
    while time.monotonic() < deadline:
        row = pair_row(args.serial)
        if int(row["pairSequence"] or 0) > int(before["pairSequence"] or 0) and row["pairOutcome"] != "running":
            break
        time.sleep(0.5)
    if row["pairOutcome"] != "ok":
        raise SystemExit(f"the phone did not take the pairing ({row['pairOutcome']}): {row['error']}")
    lines = [
        f"paired with {row['name']}: the phone will look for it at {row['hosts']} (last seen at {row['address']}), port {row['port']}",
        f"certificate {identity.fingerprint[:16]}..., secret kept in {lansync.sync_dir()}",
    ]
    # The phone tries once at once; say how that went, since it is the first proof the whole path works.
    paired_at = int(before["lastAttemptAt"] or 0)
    while time.monotonic() < deadline:
        row = pair_row(args.serial)
        if int(row["lastAttemptAt"] or 0) > paired_at and row["lastResult"] != "none":
            break
        time.sleep(1)
    result = row["lastResult"]
    if result == "ok":
        lines.append("first exchange: ok")
    else:
        lines.append(f"first exchange: {result}" + (f" ({row['error']})" if row["error"] else "")
                     + ("; start the laptop's end with `nocturne_aw.py serve` (or enable nocturne-serve.service) and the phone retries about every 15 min on Wi-Fi"
                        if result in ("unreachable", "none") else ""))
    return lines


def on_exchange_for(aw: ActivityWatch, args, log=None):
    """The server's callback: the phone's rows into ActivityWatch and phone.json, and the laptop's use back."""
    log = log or (lambda line: print(line, file=sys.stderr, flush=True))

    def on_exchange(request: lansync.Request, address: str) -> str:
        save_summary(request.device, request.nights)
        previous = lansync.last_exchange()
        buckets = [f"nocturne-sessions_{request.device}", f"nocturne-sleep_{request.device}"]
        unchanged = (previous is not None and previous.get("digest") == request.digest and previous.get("device") == request.device
                     and all(b in aw.buckets() for b in buckets))
        if not unchanged:
            rebuild_buckets(aw, request.device, request.sessions, request.nights)
        text = build_laptop_file(aw, args)
        spans = text.count("\nspan,")
        lansync.record_exchange(address, request, spans)
        log(f"exchange with {address} ({request.device}): {request.sessions.count(chr(10)) - 1} sessions, "
            f"{request.nights.count(chr(10)) - 1} nights ({'unchanged' if unchanged else 'rebuilt'}); {spans} laptop spans sent")
        return text

    return on_exchange


def serve(aw: ActivityWatch, args) -> None:
    identity = lansync.load_identity()
    try:
        server = lansync.make_server(identity, on_exchange_for(aw, args), bind=args.bind, port=args.port)
    except OSError as e:
        raise SystemExit(f"cannot listen on {args.bind}:{args.port}: {e}")
    print(f"serving Nocturne sync on {args.bind}:{args.port}, certificate {identity.fingerprint[:16]}...", file=sys.stderr, flush=True)
    server.serve_forever()


def listening(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False


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
    ran, opened_days, opens = engagement_summary(date.today())
    if ran:
        lines.append(f"indicator: ran on {ran} of the last 14 days, menu opened {opens} times on {opened_days} of them")
    last = lansync.last_exchange()
    if last:
        lines.append(f"wifi sync: last exchange {age(last['at'])} with {last['device']} at {last['address']}: {last['sessions']} sessions and "
                     f"{last['nights']} nights in, {last['spans']} laptop spans out")
    elif lansync.find_identity() is None:
        lines.append("wifi sync: not set up (run `pair` with the phone on USB)")
    else:
        lines.append("wifi sync: set up, no exchange yet")
    lines.append(f"wifi sync server: {'listening on port ' + str(lansync.PORT) if listening(lansync.PORT) else 'not running'}")
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


def sync_now(aw: ActivityWatch, args) -> None:
    """send, and for sync also phone-to-aw, over the transport already chosen in args.serial."""
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


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", default=SERVER, help="ActivityWatch server (default %(default)s)")
    parser.add_argument("--serial", help="adb device serial, when more than one is connected")
    sub = parser.add_subparsers(dest="command", required=True)

    watcher = sub.add_parser("watch")
    watcher.add_argument("--interval", type=int, default=60, help="seconds between display readings (default %(default)s)")
    watcher.add_argument("--afk", action="store_true", help="also record presence; only where no ActivityWatch AFK watcher runs")
    watcher.add_argument("--timeout", type=int, default=180, help="seconds without input before afk (default %(default)s)")

    for name in ("laptop-file", "send", "sync", "serve"):
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
        elif name == "serve":
            p.add_argument("--bind", default="0.0.0.0", help="address to listen on (default %(default)s)")
            p.add_argument("--port", type=int, default=lansync.PORT, help="port (default %(default)s)")
        else:
            p.add_argument("--if-connected", action="store_true", help="exit quietly when no phone is on adb (for a timer)")
            p.add_argument("--wifi", action="store_true", help="with no phone on USB, try the address wifi-setup remembered")

    pairing = sub.add_parser("pair", help="over USB, once: give the phone this laptop's address, certificate and secret")
    pairing.add_argument("--port", type=int, default=lansync.PORT, help="the port `serve` listens on (default %(default)s)")
    sub.add_parser("phone-to-aw")
    sub.add_parser("status")
    sub.add_parser("wifi-setup", help="over USB: let sync reach the phone over Wi-Fi until it reboots (see wifi_setup)")
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
        over_wifi = None
        if args.wifi and not phone_connected(args.serial):
            over_wifi = wifi_connect()
            args.serial = over_wifi or args.serial
        if args.if_connected and not phone_connected(args.serial):
            return
        try:
            sync_now(aw, args)
        finally:
            if over_wifi:
                # A Wi-Fi transport left connected beside USB later makes every serial-less adb command ambiguous.
                subprocess.run(["adb", "disconnect", over_wifi], capture_output=True, timeout=15)
    elif args.command == "wifi-setup":
        print(f"sync --wifi will reach the phone at {wifi_setup(args.serial)} until it reboots")
    elif args.command == "serve":
        serve(aw, args)
    elif args.command == "pair":
        print("\n".join(pair(args)))
    elif args.command == "phone-to-aw":
        for bucket, count in phone_to_aw(aw, args.serial).items():
            print(f"{bucket}: {count} events")
    elif args.command == "status":
        print("\n".join(status(aw, host_name(), args.serial)))


if __name__ == "__main__":
    main()
