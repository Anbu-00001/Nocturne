"""
When this computer was on and awake, read from the systemd journal, so that "nothing recorded its use" can be told from
"nobody was using it". ActivityWatch cannot say which: a laptop that was on all evening with the watcher not running looks
the same as one that was off. Nocturne sends the phone the difference as gaps (see `nocturne_aw.laptop_file`), so a night's
modelled light does not count an unrecorded evening as an idle one.

Everything here that decides anything is a pure function of intervals, `[start, end)` in epoch milliseconds, and is tested
without a journal. The readers at the end are thin; where the journal cannot be read they return None and no gap is sent.
"""
from __future__ import annotations

import json
import re
import subprocess

Interval = tuple[int, int]

# A watcher starts a minute or two after the journal does, and a lid closed for a moment is not worth a note.
GAP_MIN_MS = 10 * 60 * 1000


def join(intervals: list[Interval]) -> list[Interval]:
    """Sorted, disjoint intervals; touching ones join."""
    merged: list[Interval] = []
    for start, end in sorted(intervals):
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def subtract(intervals: list[Interval], cuts: list[Interval]) -> list[Interval]:
    """The parts of [intervals] outside every one of [cuts]."""
    cuts = join(cuts)
    out: list[Interval] = []
    for start, end in join(intervals):
        cursor = start
        for cut_start, cut_end in cuts:
            if cut_end <= cursor:
                continue
            if cut_start >= end:
                break
            if cut_start > cursor:
                out.append((cursor, cut_start))
            cursor = max(cursor, cut_end)
            if cursor >= end:
                break
        if cursor < end:
            out.append((cursor, end))
    return out


def unrecorded(awake: list[Interval], recorded: list[Interval], lo: int, hi: int, min_ms: int = GAP_MIN_MS) -> list[Interval]:
    """Time within [lo, hi] the computer was awake and no recording covers, in stretches of at least [min_ms]."""
    inside = [(max(a, lo), min(b, hi)) for a, b in awake]
    return [(a, b) for a, b in subtract(inside, recorded) if b - a >= min_ms]


def awake_spans(boots: list[Interval], suspends: list[Interval]) -> list[Interval]:
    """Boots without the time spent suspended."""
    return subtract(boots, suspends)


def parse_boots(text: str, now_ms: int) -> list[Interval]:
    """`journalctl --list-boots -o json`: first to last entry of each boot. The current boot, index 0, runs to now."""
    boots: list[Interval] = []
    for boot in json.loads(text):
        start = int(boot["first_entry"]) // 1000
        end = int(boot["last_entry"]) // 1000
        if boot.get("index") == 0:
            end = max(end, now_ms)
        boots.append((start, end))
    return boots


_PERFORMING = re.compile(r"Performing sleep operation")
_RETURNED = re.compile(r"System returned from sleep operation")


def parse_suspends(text: str, boots: list[Interval]) -> list[Interval]:
    """
    `journalctl -t systemd-sleep -o short-unix`: from "Performing sleep operation" to "System returned". A suspend with no
    return, which is a power cut while asleep, ends with the boot it began in.
    """
    events: list[tuple[int, bool]] = []
    for line in text.splitlines():
        stamp, _, rest = line.partition(" ")
        try:
            at = round(float(stamp) * 1000)
        except ValueError:
            continue
        if _PERFORMING.search(rest):
            events.append((at, True))
        elif _RETURNED.search(rest):
            events.append((at, False))
    events.sort()
    out: list[Interval] = []
    began: int | None = None
    for at, starts in events:
        if starts:
            if began is not None:
                out.append((began, _boot_end(began, boots)))
            began = at
        elif began is not None:
            out.append((began, at))
            began = None
    if began is not None:
        out.append((began, _boot_end(began, boots)))
    return out


def _boot_end(at: int, boots: list[Interval]) -> int:
    return next((end for start, end in boots if start <= at <= end), at)


def _journalctl(args: list[str]) -> str | None:
    try:
        done = subprocess.run(["journalctl", "--no-pager", "-q", *args], capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return done.stdout if done.returncode == 0 else None


def read_awake(since_ms: int, now_ms: int) -> list[Interval] | None:
    """
    When the computer was on and awake from [since_ms] to [now_ms], or None where the journal cannot say. Boots the journal
    no longer holds are simply absent, so what is older than its retention is never reported as a gap.
    """
    boots_text = _journalctl(["--list-boots", "-o", "json"])
    if boots_text is None:
        return None
    try:
        boots = parse_boots(boots_text, now_ms)
    except (ValueError, KeyError, TypeError):
        return None
    sleeps_text = _journalctl(["-t", "systemd-sleep", "-o", "short-unix", "--since", f"@{since_ms // 1000 - 86_400}"])
    suspends = parse_suspends(sleeps_text or "", boots)
    return [(max(a, since_ms), min(b, now_ms)) for a, b in awake_spans(boots, suspends) if b > since_ms and a < now_ms]
