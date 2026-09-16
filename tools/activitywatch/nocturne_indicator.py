#!/usr/bin/env python3
"""Nocturne in Ubuntu's top bar: a moon beside the system icons, with a menu of what the laptop knows.

Uses Ubuntu's AppIndicators (StatusNotifierItem through libayatana-appindicator, both installed and enabled on Ubuntu
24.04), not a GNOME Shell extension: nothing to install, no log-out on Wayland, and no breakage when GNOME's extension API
changes. What it shows is tonight.py's, which has no GTK in it and is tested on its own.

  python3 tools/activitywatch/nocturne_indicator.py [--interval 60] [--no-label]

The moon is dim before the evening window with the window's start beside it, bright with a star inside the window, and
faded with an amber dot when presence is not being recorded. It never notifies or pops up: the menu opens on a click.
"""

from __future__ import annotations

import argparse
import signal
import subprocess
import sys
import threading
from pathlib import Path

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("AyatanaAppIndicator3", "0.1")
from gi.repository import AyatanaAppIndicator3 as AppIndicator  # noqa: E402
from gi.repository import GLib, Gtk  # noqa: E402

from awclient import SERVER, ActivityWatch, event_interval, now_ms  # noqa: E402
from laptop_state import afk_bucket, display_bucket, host_name  # noqa: E402
from tonight import STOPPED_AFTER_MS, LaptopNow, PhoneSummary, View, view  # noqa: E402

HERE = Path(__file__).resolve().parent
LINES = 8


def read_laptop(aw: ActivityWatch, host: str) -> LaptopNow:
    """Presence since the last noon (26 h covers it) and the display state, if the watcher reported recently."""
    now = now_ms()
    try:
        afk = aw.events(afk_bucket(host), now - 26 * 3_600_000, now)
        shown = aw.latest(display_bucket(host))
    except OSError:
        return LaptopNow(afk=None, backlight=None, night_light=None)
    if shown is None or now - event_interval(shown)[1] > STOPPED_AFTER_MS:
        return LaptopNow(afk=afk, backlight=None, night_light=None)
    data = shown.get("data", {})
    return LaptopNow(afk=afk, backlight=data.get("backlight"), night_light=data.get("nightLight"))


class Indicator:
    def __init__(self, aw: ActivityWatch, interval_s: int, show_label: bool):
        self.aw = aw
        self.host = host_name()
        self.show_label = show_label
        self.sync_note: str | None = None
        self.busy = threading.Lock()

        self.indicator = AppIndicator.Indicator.new("nocturne", "nocturne-day", AppIndicator.IndicatorCategory.APPLICATION_STATUS)
        self.indicator.set_icon_theme_path(str(HERE / "icons"))
        self.indicator.set_title("Nocturne")
        self.indicator.set_status(AppIndicator.IndicatorStatus.ACTIVE)

        menu = Gtk.Menu()
        self.lines = []
        for _ in range(LINES):
            item = Gtk.MenuItem(label="")
            item.set_sensitive(False)
            menu.append(item)
            self.lines.append(item)
        menu.append(Gtk.SeparatorMenuItem())
        self.sync_item = Gtk.MenuItem(label="Sync with the phone now")
        self.sync_item.connect("activate", lambda _: self.sync())
        menu.append(self.sync_item)
        open_aw = Gtk.MenuItem(label="Open ActivityWatch")
        open_aw.connect("activate", lambda _: subprocess.Popen(["xdg-open", SERVER]))
        menu.append(open_aw)
        quit_item = Gtk.MenuItem(label="Quit")
        quit_item.connect("activate", lambda _: Gtk.main_quit())
        menu.append(quit_item)
        menu.show_all()
        self.indicator.set_menu(menu)

        self.refresh()
        GLib.timeout_add_seconds(interval_s, self._tick)

    def _tick(self) -> bool:
        self.refresh()
        return True

    def refresh(self) -> None:
        """Reads off the main loop, so a slow server never freezes the menu."""
        def work():
            shown = view(now_ms(), PhoneSummary.load(), read_laptop(self.aw, self.host), self.show_label)
            GLib.idle_add(self.apply, shown)

        threading.Thread(target=work, daemon=True).start()

    def apply(self, shown: View) -> bool:
        self.indicator.set_icon_full(f"nocturne-{shown.icon}", {"day": "Nocturne", "evening": "Nocturne, evening window",
                                                                 "stopped": "Nocturne, not recording"}[shown.icon])
        self.indicator.set_label(shown.label, "not recording")
        lines = shown.lines + ([self.sync_note] if self.sync_note else [])
        for item, text in zip(self.lines, lines + [""] * LINES):
            item.set_label(text)
            item.set_visible(bool(text))
        return False

    def sync(self) -> None:
        if not self.busy.acquire(blocking=False):
            return
        self.sync_item.set_sensitive(False)
        self.sync_note = "Syncing with the phone"
        self.refresh()

        def work():
            try:
                result = subprocess.run(
                    [sys.executable, str(HERE / "nocturne_aw.py"), "--server", self.aw.base.removesuffix("/api/0"), "sync", "--if-connected"],
                    capture_output=True, text=True, timeout=300,
                )
                out = (result.stdout.strip().splitlines() or [""])[0]
                note = ("Sync: " + out) if result.returncode == 0 and out else \
                    "No phone on adb; nothing synced" if result.returncode == 0 else "Sync did not finish"
            except (OSError, subprocess.SubprocessError):
                note = "Sync did not finish"
            GLib.idle_add(self._synced, note)

        threading.Thread(target=work, daemon=True).start()

    def _synced(self, note: str) -> bool:
        self.sync_note = note
        self.sync_item.set_sensitive(True)
        self.busy.release()
        self.refresh()
        return False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", default=SERVER)
    parser.add_argument("--interval", type=int, default=60, help="seconds between refreshes (default %(default)s)")
    parser.add_argument("--no-label", action="store_true", help="the moon alone, without text beside it")
    args = parser.parse_args()
    Indicator(ActivityWatch(args.server), args.interval, show_label=not args.no_label)
    for sig in (signal.SIGINT, signal.SIGTERM):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, sig, Gtk.main_quit)
    Gtk.main()


if __name__ == "__main__":
    main()
