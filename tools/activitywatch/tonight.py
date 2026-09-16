"""What the laptop's top-bar indicator shows, as a pure function of what it can read. No GTK here, so it is tested plainly.

Two sources, of different freshness:
- the laptop's own ActivityWatch buckets, live: whether presence and backlight are still being recorded, and time at the
  laptop today;
- the phone's evening window and last night, as of the last `sync` (phone.json): slow-changing facts that a stale copy
  still gets right.

Copy follows the app's :tone rules: numbers, not adjectives; no exclamation marks, no emoji (test_tonight.py audits it).
"""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from awclient import event_interval

MINUTE_MS = 60_000
DAY_MS = 86_400_000
# Presence heartbeats come every 5 s and display readings every minute; this long without either means the watcher stopped.
STOPPED_AFTER_MS = 5 * MINUTE_MS


def state_dir() -> Path:
    return Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "nocturne"


@dataclass(frozen=True)
class PhoneSummary:
    """Written by `nocturne_aw.py sync` from the phone's nights."""

    synced_at: int
    device: str
    offset_minutes: int
    window_start_minute: int | None
    window_end_minute: int | None
    window_personal: bool | None
    last_night_date: str | None
    last_onset: int | None
    last_wake: int | None
    last_no_sleep: bool
    last_source: str | None

    def save(self, path: Path | None = None) -> Path:
        path = path or state_dir() / "phone.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temp = path.with_suffix(".tmp")
        temp.write_text(json.dumps(asdict(self), indent=1))
        temp.replace(path)
        return path

    @staticmethod
    def load(path: Path | None = None) -> PhoneSummary | None:
        path = path or state_dir() / "phone.json"
        try:
            return PhoneSummary(**json.loads(path.read_text()))
        except (OSError, ValueError, TypeError):
            return None

    def keeping_window_of(self, previous: PhoneSummary | None) -> PhoneSummary:
        """An app too old to send its evening window keeps the last one known rather than forgetting it."""
        if self.window_start_minute is not None or previous is None or previous.window_start_minute is None:
            return self
        return PhoneSummary(**{**asdict(self), "window_start_minute": previous.window_start_minute,
                               "window_end_minute": previous.window_end_minute, "window_personal": previous.window_personal})

    @staticmethod
    def from_nights(rows: list[dict], synced_at: int, device: str) -> PhoneSummary | None:
        """From the phone's nights CSV rows: the latest night's window, and the latest night that has a verdict."""
        if not rows:
            return None
        rows = sorted(rows, key=lambda r: r["dateOfNight"])
        latest = rows[-1]
        judged = [r for r in rows if r["noSleep"] == "true" or (r["onsetTs"] and r["wakeTs"])]
        last = judged[-1] if judged else None

        def number(row: dict, key: str) -> int | None:
            value = row.get(key)
            return int(value) if value not in (None, "") else None

        return PhoneSummary(
            synced_at=synced_at,
            device=device,
            offset_minutes=int(latest["utcOffsetMinutes"]),
            window_start_minute=number(latest, "eveningWindowStartMinute"),
            window_end_minute=number(latest, "eveningWindowEndMinute"),
            window_personal=None if latest.get("windowPersonalised") in (None, "") else latest["windowPersonalised"] == "true",
            last_night_date=last["dateOfNight"] if last else None,
            last_onset=number(last, "onsetTs") if last else None,
            last_wake=number(last, "wakeTs") if last else None,
            last_no_sleep=bool(last and last["noSleep"] == "true"),
            last_source=last["source"] if last else None,
        )


@dataclass(frozen=True)
class LaptopNow:
    """Read from ActivityWatch just now. [afk] is None when the server did not answer."""

    afk: list[dict] | None
    #: Only when the display watcher reported in the last few minutes.
    backlight: float | None
    night_light: bool | None


@dataclass(frozen=True)
class View:
    #: "day", "evening" or "stopped": which icon.
    icon: str
    #: Short text beside the icon; empty for none.
    label: str
    lines: list[str]


def clock(ts: int, offset_minutes: int) -> str:
    return (datetime.fromtimestamp(ts / 1000, tz=timezone.utc) + timedelta(minutes=offset_minutes)).strftime("%H:%M")


def minute_text(minute: int) -> str:
    return f"{minute // 60:02d}:{minute % 60:02d}"


def duration(ms: int) -> str:
    """The app's Tone.duration: 3h 12m, 45m."""
    minutes = max(0, ms) // MINUTE_MS
    return f"{minutes // 60}h {minutes % 60}m" if minutes >= 60 else f"{minutes}m"


def ago(ms: int) -> str:
    minutes = max(0, ms) // MINUTE_MS
    if minutes < 1:
        return "just now"
    if minutes < 120:
        return f"{minutes} min ago"
    return f"{minutes // 60} h ago"


def in_window(now: int, offset_minutes: int, start: int, end: int) -> bool:
    minute = ((now + offset_minutes * MINUTE_MS) % DAY_MS) // MINUTE_MS
    return start <= minute < end if start <= end else minute >= start or minute < end


def window_occurrence(now: int, offset_minutes: int, start: int, end: int) -> tuple[int, int]:
    """The window now falls in, or else the one that ended most recently, as UTC instants."""
    local_midnight = (now + offset_minutes * MINUTE_MS) // DAY_MS * DAY_MS - offset_minutes * MINUTE_MS
    length = ((end - start) % 1440 or 1440) * MINUTE_MS
    for day in (0, -1, -2):
        begin = local_midnight + day * DAY_MS + start * MINUTE_MS
        if begin <= now:
            return begin, begin + length
    raise AssertionError("unreachable")


def not_afk_ms(afk: list[dict], from_ts: int, to_ts: int) -> int:
    """Time not-afk inside [from_ts, to_ts), overlapping events counted once."""
    spans = sorted(event_interval(e) for e in afk if e.get("data", {}).get("status") == "not-afk")
    total, reach = 0, from_ts
    for start, end in spans:
        start, end = max(start, reach), min(end, to_ts)
        if end > start:
            total += end - start
            reach = end
    return total


def local_offset_minutes() -> int:
    offset = datetime.now().astimezone().utcoffset()
    return round(offset.total_seconds() / 60) if offset else 0


def view(now: int, phone: PhoneSummary | None, laptop: LaptopNow, show_label: bool = True) -> View:
    offset = phone.offset_minutes if phone else local_offset_minutes()
    window = (
        (phone.window_start_minute, phone.window_end_minute)
        if phone and phone.window_start_minute is not None and phone.window_end_minute is not None
        else None
    )
    lines: list[str] = []

    last_afk_end = max((event_interval(e)[1] for e in laptop.afk or []), default=None)
    stopped = laptop.afk is None or last_afk_end is None or now - last_afk_end > STOPPED_AFTER_MS
    if laptop.afk is None:
        lines.append("Not recording: ActivityWatch's server did not answer")
    elif stopped:
        lines.append(f"Not recording presence since {clock(last_afk_end, offset)}" if last_afk_end else "Not recording presence today")

    if window:
        kind = "personal" if phone and phone.window_personal else "provisional"
        lines.append(f"Evening window {minute_text(window[0])} to {minute_text(window[1])} ({kind})")

    if laptop.afk is not None:
        noon = (now + offset * MINUTE_MS - 12 * 60 * MINUTE_MS) // DAY_MS * DAY_MS + 12 * 60 * MINUTE_MS - offset * MINUTE_MS
        # Before recording began nothing is known, which is not the same as nobody at the laptop.
        recorded_from = min((event_interval(e)[0] for e in laptop.afk), default=now)
        since = "noon" if recorded_from <= noon + STOPPED_AFTER_MS else f"{clock(recorded_from, offset)}, when recording began"
        line = f"At the laptop since {since}: {duration(not_afk_ms(laptop.afk, noon, now))}"
        if window:
            # Only this night's window: one that began after noon. A window that ended this morning is last night's.
            begin, finish = window_occurrence(now, offset, *window)
            if begin >= noon:
                line += f", {duration(not_afk_ms(laptop.afk, begin, min(finish, now)))} of it in the evening window"
        lines.append(line)

    parts = []
    if laptop.backlight is not None:
        parts.append(f"backlight {round(laptop.backlight * 100)}%")
    if laptop.night_light is not None:
        parts.append("Night Light on" if laptop.night_light else "Night Light off")
    if parts:
        lines.append("Laptop screen: " + ", ".join(parts))

    if phone and phone.last_night_date:
        night = datetime.fromisoformat(phone.last_night_date).strftime("%a %d %b")
        if phone.last_no_sleep:
            lines.append(f"Night of {night}: no sleep")
        elif phone.last_onset and phone.last_wake:
            whose = "your times" if phone.last_source == "USER_REPORTED" else "estimate"
            lines.append(f"Night of {night}: asleep {clock(phone.last_onset, offset)} to {clock(phone.last_wake, offset)} ({whose})")
    lines.append(f"Phone synced {ago(now - phone.synced_at)}" if phone else "Phone not synced yet")

    if stopped:
        return View("stopped", "not recording" if show_label else "", lines)
    if window and in_window(now, offset, *window):
        return View("evening", "", lines)
    return View("day", minute_text(window[0]) if show_label and window else "", lines)
