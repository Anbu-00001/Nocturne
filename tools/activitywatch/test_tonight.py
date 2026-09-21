"""Tests for tonight.py, the indicator's content. From the repository: python3 -m unittest discover -s tools/activitywatch."""

from __future__ import annotations

import re
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from awclient import iso
from tonight import LaptopNow, PhoneSummary, View, duration, in_window, not_afk_ms, view, window_occurrence

IST = timezone(timedelta(hours=5, minutes=30))
MIN = 60_000


def ist(text: str) -> int:
    return int(datetime.fromisoformat(text).replace(tzinfo=IST).timestamp() * 1000)


def afk(start: int, end: int, status: str = "not-afk") -> dict:
    return {"timestamp": iso(start), "duration": (end - start) / 1000, "data": {"status": status}}


PHONE = PhoneSummary(
    synced_at=ist("2026-09-17T00:55"), device="CPH2591", offset_minutes=330,
    window_start_minute=34, window_end_minute=750, window_personal=True,
    last_night_date="2026-09-15", last_onset=ist("2026-09-16T04:16"), last_wake=ist("2026-09-16T14:23"),
    last_no_sleep=False, last_source="INFERRED",
)


class Windows(unittest.TestCase):
    def test_a_window_after_midnight_and_one_across_it(self):
        self.assertTrue(in_window(ist("2026-09-17T01:00"), 330, 34, 750))
        self.assertFalse(in_window(ist("2026-09-16T23:30"), 330, 34, 750))
        self.assertTrue(in_window(ist("2026-09-16T23:30"), 330, 21 * 60, 7 * 60))
        self.assertTrue(in_window(ist("2026-09-17T06:59"), 330, 21 * 60, 7 * 60))
        self.assertEqual((ist("2026-09-17T00:34"), ist("2026-09-17T12:30")), window_occurrence(ist("2026-09-17T01:00"), 330, 34, 750))
        self.assertEqual((ist("2026-09-16T00:34"), ist("2026-09-16T12:30")), window_occurrence(ist("2026-09-16T23:30"), 330, 34, 750))
        self.assertEqual((ist("2026-09-16T21:00"), ist("2026-09-17T07:00")), window_occurrence(ist("2026-09-17T02:00"), 330, 21 * 60, 7 * 60))

    def test_overlapping_presence_counts_once(self):
        events = [afk(0, 10 * MIN), afk(5 * MIN, 20 * MIN), afk(20 * MIN, 30 * MIN, "afk"), afk(40 * MIN, 50 * MIN)]
        self.assertEqual(25 * MIN, not_afk_ms(events, 0, 45 * MIN))
        self.assertEqual("1h 5m", duration(65 * MIN))
        self.assertEqual("45m", duration(45 * MIN))


class Views(unittest.TestCase):
    def test_the_label_names_the_evening_window_only_in_the_hours_before_it(self):
        laptop = LaptopNow(afk=[afk(ist("2026-09-16T18:00"), ist("2026-09-16T18:35"))], backlight=None, night_light=None)
        self.assertEqual("", view(ist("2026-09-16T18:35"), PHONE, laptop).label)
        self.assertEqual("", view(ist("2026-09-16T21:33"), PHONE, LaptopNow(afk=[afk(ist("2026-09-16T21:30"), ist("2026-09-16T21:33"))], backlight=None, night_light=None)).label)
        self.assertEqual("evening at 00:34", view(ist("2026-09-16T21:34"), PHONE, LaptopNow(afk=[afk(ist("2026-09-16T21:30"), ist("2026-09-16T21:34"))], backlight=None, night_light=None)).label)

    def test_before_the_window_the_label_is_its_start_and_the_menu_says_the_rest(self):
        now = ist("2026-09-16T23:30")
        laptop = LaptopNow(afk=[afk(ist("2026-09-16T11:00"), ist("2026-09-16T21:00"), "afk"), afk(ist("2026-09-16T21:00"), now)], backlight=0.059, night_light=False)
        phone = PhoneSummary(**{**PHONE.__dict__, "synced_at": ist("2026-09-16T20:30")})
        self.assertEqual(View("day", "evening at 00:34", [
            "Evening window 00:34 to 12:30 (personal)",
            "At the laptop since noon: 2h 30m",
            "Laptop screen: backlight 6%, Night Light off",
            "Night of Tue 15 Sep: asleep 04:16 to 14:23 (estimate)",
            "Phone synced 3 h ago",
        ]), view(now, phone, laptop))

    def test_inside_the_window_the_icon_changes_and_window_time_is_counted(self):
        now = ist("2026-09-17T01:10")
        laptop = LaptopNow(afk=[afk(ist("2026-09-16T23:00"), now)], backlight=None, night_light=None)
        v = view(now, PHONE, laptop)
        self.assertEqual("evening", v.icon)
        self.assertEqual("", v.label)
        self.assertIn("At the laptop since 23:00, when recording began: 2h 10m, 36m of it in the evening window", v.lines)
        self.assertIn("Phone synced 15 min ago", v.lines)

    def test_a_stopped_watcher_is_said_before_anything_else(self):
        now = ist("2026-09-17T01:10")
        stale = view(now, PHONE, LaptopNow(afk=[afk(ist("2026-09-16T23:00"), ist("2026-09-17T00:40"))], backlight=None, night_light=None))
        self.assertEqual(("stopped", "not recording"), (stale.icon, stale.label))
        self.assertEqual("Not recording presence since 00:40", stale.lines[0])
        down = view(now, None, LaptopNow(afk=None, backlight=None, night_light=None), show_label=False)
        self.assertEqual(View("stopped", "", ["Not recording: ActivityWatch's server did not answer", "Phone not synced yet"]), down)

    def test_the_copy_keeps_the_app_s_tone(self):
        now = ist("2026-09-17T01:10")
        views = [
            view(now, PHONE, LaptopNow(afk=[afk(ist("2026-09-16T23:00"), now)], backlight=0.5, night_light=True)),
            view(now, PhoneSummary(**{**PHONE.__dict__, "last_no_sleep": True}), LaptopNow(afk=[], backlight=None, night_light=None)),
        ]
        banned = ["waste", "bad", "terrible", "awful", "addict", "shame", "guilt", "lazy", "you failed", "unhealthy", "ruin", "you should", "you need to", "too much", "again"]
        for v in views:
            for line in v.lines + [v.label]:
                self.assertNotIn("!", line)
                self.assertFalse(any(re.search(rf"\b{re.escape(w)}", line, re.IGNORECASE) for w in banned), line)
                self.assertTrue(all(ord(ch) < 0x2000 for ch in line), line)


class Staleness(unittest.TestCase):
    def test_a_window_from_last_week_and_an_old_night_say_so(self):
        # Synced Thu 17 Sept; now Mon 21 Sept 18:30, after the phone fixed this week's window.
        old = PhoneSummary(**{**PHONE.__dict__, "synced_at": ist("2026-09-17T00:55")})
        v = view(ist("2026-09-21T18:30"), old, LaptopNow(afk=[afk(ist("2026-09-21T12:00"), ist("2026-09-21T18:30"))], backlight=None, night_light=None))
        self.assertIn("Evening window 00:34 to 12:30 (personal, from last week's sync)", v.lines)
        self.assertIn("Night of Tue 15 Sep, the latest synced: asleep 04:16 to 14:23 (estimate)", v.lines)
        self.assertIn("Phone synced 4 days ago", v.lines)
        # The same sync, read the day after: this week's window, yesterday's night.
        fresh = view(ist("2026-09-17T12:00"), old, LaptopNow(afk=[afk(ist("2026-09-17T11:58"), ist("2026-09-17T12:00"))], backlight=None, night_light=None))
        self.assertIn("Evening window 00:34 to 12:30 (personal)", fresh.lines)
        self.assertIn("Night of Tue 15 Sep: asleep 04:16 to 14:23 (estimate)", fresh.lines)

    def test_weeks_start_on_local_monday(self):
        from tonight import week_start
        self.assertEqual(ist("2026-09-21T00:00"), week_start(ist("2026-09-21T18:30"), 330))
        self.assertEqual(ist("2026-09-14T00:00"), week_start(ist("2026-09-20T23:59"), 330))
        self.assertEqual(ist("2026-09-21T00:00"), week_start(ist("2026-09-21T00:00"), 330))


class Summary(unittest.TestCase):
    def test_the_phone_s_nights_give_the_latest_window_and_the_latest_judged_night(self):
        header = "dateOfNight,onsetTs,wakeTs,confidence,source,noSleep,utcOffsetMinutes,suppressionLowPct,suppressionPct,suppressionHighPct,eveningScreenMinutes,lightLaptopMinutes,eveningWindowStartMinute,eveningWindowEndMinute,windowPersonalised"
        text = "\n".join([
            header,
            f"2026-09-15,{ist('2026-09-16T04:16')},{ist('2026-09-16T14:23')},0.58,INFERRED,false,330,,,,155,0,34,750,true",
            "2026-09-16,,,0.0,INFERRED,false,330,,,,40,0,34,750,true",
        ])
        import csv
        import io
        summary = PhoneSummary.from_nights(list(csv.DictReader(io.StringIO(text))), synced_at=5, device="CPH2591")
        self.assertEqual(PHONE.__class__(**{**PHONE.__dict__, "synced_at": 5}), summary)
        with tempfile.TemporaryDirectory() as root:
            path = summary.save(Path(root, "phone.json"))
            self.assertEqual(summary, PhoneSummary.load(path))
        self.assertIsNone(PhoneSummary.load(Path("/nonexistent/phone.json")))

    def test_an_app_without_window_columns_leaves_the_window_unknown(self):
        rows = [{"dateOfNight": "2026-09-16", "onsetTs": "", "wakeTs": "", "confidence": "0", "source": "INFERRED", "noSleep": "true", "utcOffsetMinutes": "330"}]
        summary = PhoneSummary.from_nights(rows, synced_at=1, device="x")
        self.assertIsNone(summary.window_start_minute)
        self.assertTrue(summary.last_no_sleep)
        kept = summary.keeping_window_of(PHONE)
        self.assertEqual((34, 750, True, 1), (kept.window_start_minute, kept.window_end_minute, kept.window_personal, kept.synced_at))
        self.assertIs(PHONE, PHONE.keeping_window_of(summary))


if __name__ == "__main__":
    unittest.main()
