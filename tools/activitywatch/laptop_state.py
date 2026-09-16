"""What this laptop can say about its screen and its user, read from GNOME and sysfs, and the watcher that records it.

ActivityWatch's watchers are meant to record presence. On GNOME Wayland the one that works there, awatcher, needs the
"Focused Window D-Bus" Shell extension for its window watcher and quits without it, taking its idle watcher along (seen on
Ubuntu 24.04, GNOME 46, awatcher 0.4.0). So `watch --afk` can report presence itself, by awatcher's own method for GNOME
(Mutter's IdleMonitor) and aw-watcher-afk's rule, into the same aw-watcher-afk_<host> bucket. Leave --afk off wherever
awatcher or aw-watcher-afk runs, so the bucket has one writer.
"""

from __future__ import annotations

import re
import socket
import subprocess
import sys
import time
import urllib.error
from dataclasses import dataclass
from pathlib import Path

from awclient import ActivityWatch, iso, now_ms


def host_name() -> str:
    """The name ActivityWatch's watchers use for this machine, fitted to Nocturne's file format."""
    return re.sub(r"[^A-Za-z0-9._-]", "-", socket.gethostname())[:64] or "laptop"


def display_bucket(host: str) -> str:
    return f"nocturne-display_{host}"


def afk_bucket(host: str) -> str:
    return f"aw-watcher-afk_{host}"


def panel_size_mm(drm: Path = Path("/sys/class/drm")) -> tuple[int, int] | None:
    """The built-in panel's image size from its EDID's first detailed timing descriptor."""
    for path in sorted(drm.glob("card*-eDP-*/edid")) + sorted(drm.glob("card*-LVDS-*/edid")):
        edid = path.read_bytes()
        if len(edid) >= 72:
            d = edid[54:72]
            width = d[12] | ((d[14] >> 4) << 8)
            height = d[13] | ((d[14] & 0x0F) << 8)
            if width and height:
                return width, height
    return None


def backlight_share(backlight: Path = Path("/sys/class/backlight")) -> float | None:
    """The backlight's level as a share of its range. Linux drivers set a PWM duty cycle, close to linear in luminance."""
    for device in sorted(backlight.glob("*")):
        try:
            level = int((device / "actual_brightness").read_text())
            peak = int((device / "max_brightness").read_text())
        except (OSError, ValueError):
            continue
        if peak > 0:
            return min(max(level / peak, 0.0), 1.0)
    return None


def _gdbus(*args: str) -> str | None:
    try:
        return subprocess.run(["gdbus", "call", "--session", *args], capture_output=True, text=True, timeout=5, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return None


def night_light_active() -> bool | None:
    """GNOME's own answer to whether Night Light is warming the screen now, its schedule included."""
    out = _gdbus("--dest", "org.gnome.SettingsDaemon.Color", "--object-path", "/org/gnome/SettingsDaemon/Color",
                 "--method", "org.freedesktop.DBus.Properties.Get", "org.gnome.SettingsDaemon.Color", "NightLightActive")
    if out is None:
        return None
    return True if "true" in out else False if "false" in out else None


def idle_ms() -> int | None:
    """Milliseconds since the last keyboard or pointer input, from Mutter, as awatcher's GNOME idle watcher reads it."""
    out = _gdbus("--dest", "org.gnome.Mutter.IdleMonitor", "--object-path", "/org/gnome/Mutter/IdleMonitor/Core",
                 "--method", "org.gnome.Mutter.IdleMonitor.GetIdletime")
    m = re.search(r"uint64 (\d+)", out or "")
    return int(m.group(1)) if m else None


@dataclass(frozen=True)
class Beat:
    afk: bool
    timestamp: int
    duration_s: float


class AfkTracker:
    """
    awatcher's rule for presence, from aw-watcher-afk: someone is not-afk until [timeout_s] pass without input, then afk
    from their last input until the next. Each update returns the heartbeats to send. Neither stretch's heartbeats start
    before the stretch did: aw-server-rust merges a heartbeat into an event only if it starts at or after that event's
    start (checked on 0.13.2), and "now minus idle time" read a millisecond early would split one stretch into many.
    """

    def __init__(self, timeout_s: int = 180):
        self.timeout_ms = timeout_s * 1000
        self.afk_since: int | None = None
        self.active_since: int | None = None

    @property
    def afk(self) -> bool:
        return self.afk_since is not None

    def update(self, now: int, idle: int) -> list[Beat]:
        last_input = now - idle
        since = self.afk_since
        if since is not None and idle < self.timeout_ms:
            self.afk_since = None
            self.active_since = last_input
            return [Beat(True, since, max(0, last_input - 1 - since) / 1000), Beat(False, last_input, 0.0)]
        if since is None and idle >= self.timeout_ms:
            last_input = max(last_input, self.active_since or last_input)
            self.afk_since = last_input
            self.active_since = None
            return [Beat(False, last_input, 0.0), Beat(True, last_input, (now - last_input) / 1000)]
        if since is not None:
            return [Beat(True, since, (now - since) / 1000)]
        if self.active_since is None:
            self.active_since = last_input
        return [Beat(False, max(last_input, self.active_since), 0.0)]


def watch(aw: ActivityWatch, host: str, display_every_s: int = 60, afk: bool = False, poll_s: int = 5, timeout_s: int = 180) -> None:
    """Records display state every [display_every_s] and, with [afk], presence every [poll_s], until stopped."""
    tracker = AfkTracker(timeout_s)
    ready = False
    last_display = 0.0
    while True:
        try:
            if not ready:
                aw.create_bucket(display_bucket(host), "nocturne.display", hostname=host)
                if afk:
                    aw.create_bucket(afk_bucket(host), "afkstatus", hostname=host, client="nocturne")
                ready = True
            if afk:
                idle = idle_ms()
                if idle is not None:
                    for beat in tracker.update(now_ms(), idle):
                        status = "afk" if beat.afk else "not-afk"
                        event = {"timestamp": iso(beat.timestamp), "duration": beat.duration_s, "data": {"status": status}}
                        aw.heartbeat(afk_bucket(host), event, pulsetime=timeout_s + poll_s)
            if time.monotonic() - last_display >= display_every_s:
                share = backlight_share()
                data = {"backlight": None if share is None else round(share, 3), "nightLight": night_light_active()}
                aw.heartbeat(display_bucket(host), {"timestamp": iso(now_ms()), "duration": 0, "data": data}, pulsetime=display_every_s + 30)
                last_display = time.monotonic()
        except (OSError, urllib.error.URLError) as e:
            print(f"watch: {e}", file=sys.stderr, flush=True)
            ready = False
        time.sleep(poll_s if afk else display_every_s)
