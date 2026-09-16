"""Tests for nocturne_aw.py. From the repository: python3 -m unittest discover -s tools/activitywatch (standard library only)."""

from __future__ import annotations

import re
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

import nocturne_aw as aw
from fake_activitywatch import FakeActivityWatch, FakeServer

REPO = Path(__file__).resolve().parents[2]
IST = timezone(timedelta(hours=5, minutes=30))


def ist(text: str) -> int:
    return int(datetime.fromisoformat(text).replace(tzinfo=IST).timestamp() * 1000)


def event(start: int, end: int, **data) -> dict:
    return {"timestamp": aw.iso(start), "duration": (end - start) / 1000, "data": data}


class Timestamps(unittest.TestCase):
    def test_activitywatch_timestamps_read_to_the_millisecond(self):
        self.assertEqual(1789582586883, aw.parse_ts("2026-09-16T18:16:26.883026181Z"))
        self.assertEqual(aw.parse_ts("2026-09-16T23:46:26.5+05:30"), aw.parse_ts("2026-09-16T18:16:26.500Z"))
        self.assertEqual(1789582586000, aw.parse_ts("2026-09-16T18:16:26Z"))
        self.assertEqual("2026-09-16T18:16:26.883Z", aw.iso(1789582586883))
        with self.assertRaises(ValueError):
            aw.parse_ts("16 Sept")


class LaptopFile(unittest.TestCase):
    display = aw.Display("workbook", 345, 215, 2.0, 250.0)

    def test_events_become_the_shared_sample_the_phone_parses(self):
        # core-model's LaptopFileTest reads this same file.
        sample = (REPO / "core-model/src/test/resources/laptop/sample.csv").read_text()
        afk = [
            event(ist("2026-09-14T21:10"), ist("2026-09-14T21:30"), status="afk"),
            event(ist("2026-09-14T21:30"), ist("2026-09-15T00:31"), status="not-afk"),
            event(ist("2026-09-15T03:12"), ist("2026-09-15T03:12"), status="not-afk"),
        ]
        shown = [
            event(ist("2026-09-14T21:00"), ist("2026-09-14T23:09"), backlight=0.06, nightLight=False),
            event(ist("2026-09-14T23:10"), ist("2026-09-15T00:40"), backlight=0.06, nightLight=True),
        ]
        spans = aw.spans_from_events(afk, shown, reach_ms=90_000)
        text = aw.laptop_file(self.display, ist("2026-09-14T18:00"), ist("2026-09-15T06:00"), spans)
        self.assertEqual(sample, text)

    def test_overlapping_activity_joins_and_display_gaps_are_unknown(self):
        afk = [
            event(ist("2026-09-16T22:00"), ist("2026-09-16T22:40"), status="not-afk"),
            event(ist("2026-09-16T22:30"), ist("2026-09-16T23:00"), status="not-afk"),
        ]
        shown = [
            event(ist("2026-09-16T21:50"), ist("2026-09-16T22:10"), backlight=0.5, nightLight=None),
            # Watcher stopped from 22:11:30 to 22:20.
            event(ist("2026-09-16T22:20"), ist("2026-09-16T23:05"), backlight=0.5, nightLight=None),
        ]
        spans = aw.spans_from_events(afk, shown, reach_ms=90_000)
        self.assertEqual(
            [
                aw.Span(ist("2026-09-16T22:00"), ist("2026-09-16T22:11:30"), 0.5, None),
                aw.Span(ist("2026-09-16T22:11:30"), ist("2026-09-16T22:20"), None, None),
                aw.Span(ist("2026-09-16T22:20"), ist("2026-09-16T23:00"), 0.5, None),
            ],
            spans,
        )

    def test_spans_starting_outside_the_coverage_are_left_out(self):
        spans = [aw.Span(100, 200, None, None), aw.Span(300, 400, 0.25, False), aw.Span(500, 600, None, True)]
        text = aw.laptop_file(self.display, 250, 500, spans)
        self.assertEqual(["span,workbook,300,400,0.25,0"], [line for line in text.splitlines() if line.startswith("span")])
        with self.assertRaises(ValueError):
            aw.laptop_file(aw.Display("my laptop", 1, 1, 0.0, 1.0), 0, 1, [])


class PhoneRows(unittest.TestCase):
    def kotlin_constant(self, name: str) -> str:
        source = (REPO / "data/src/main/kotlin/io/github/anbu00001/nocturne/data/LaptopExport.kt").read_text()
        body = re.search(rf"const val {name} =\s*((?:\"[^\"]*\"\s*\+?\s*)+)", source)
        assert body, name
        return "".join(re.findall(r"\"([^\"]*)\"", body.group(1)))

    def test_sessions_and_nights_read_with_the_phone_s_own_headers(self):
        sessions = self.kotlin_constant("SESSIONS_HEADER") + "\n" + "1789401600000,1789401690000,1789401690000,SHORT,true,UNKNOWN,false,com.whatsapp,330,2026-09-14\n"
        self.assertEqual(
            [{"timestamp": "2026-09-14T16:00:00.000Z", "duration": 90.0,
              "data": {"kind": "SHORT", "unlocked": True, "trigger": "UNKNOWN", "glance": False, "app": "com.whatsapp"}}],
            aw.session_events(sessions),
        )
        nights = self.kotlin_constant("NIGHTS_HEADER") + "\n" + (
            "2026-09-14,1789412460000,1789437600000,0.53,INFERRED,false,330,,,,40,0\n"
            "2026-09-15,,,0.9,USER_REPORTED,true,330,,,,12,0\n"
        )
        self.assertEqual(
            [{"timestamp": "2026-09-14T19:01:00.000Z", "duration": 25140.0, "data": {"night": "2026-09-14", "source": "INFERRED", "confidence": 0.53}}],
            aw.sleep_events(nights),
        )

    def test_import_status_keeps_an_error_with_commas_whole(self):
        ok = aw.parse_import_status("Row: 0 sequence=4, outcome=ok, finishedAt=1789412460000, hosts=anbunew, spans=12, added=3, removed=1, nights=4, error=NULL\n")
        self.assertEqual({"sequence": "4", "outcome": "ok", "finishedAt": "1789412460000", "hosts": "anbunew", "spans": "12",
                          "added": "3", "removed": "1", "nights": "4", "error": None}, ok)
        failed = aw.parse_import_status("Row: 0 sequence=5, outcome=failed, finishedAt=1, hosts=, spans=0, added=0, removed=0, nights=0, error=line 4: bad number \"x\", or worse")
        self.assertEqual("line 4: bad number \"x\", or worse", failed["error"])
        with self.assertRaises(RuntimeError):
            aw.parse_import_status("No result found.")


class Server(unittest.TestCase):
    def setUp(self):
        self.fake = FakeServer()
        self.client = self.fake.__enter__()

    def tearDown(self):
        self.fake.__exit__(None, None, None)

    def test_phone_buckets_are_rebuilt_whole_on_every_read(self):
        sessions = "startTs,endTs,lastActivityTs,kind,unlocked,trigger,countsAsGlance,dominantPackage,utcOffsetMinutes,nightDate\n" + \
            "1000,2000,2000,GLANCE_NO_UNLOCK,false,NOTIFICATION,true,,330,2026-09-14\n"
        nights = "dateOfNight,onsetTs,wakeTs,confidence,source,noSleep,utcOffsetMinutes,suppressionLowPct,suppressionPct,suppressionHighPct,eveningScreenMinutes,lightLaptopMinutes\n"

        def phone(*args, stdin=None, serial=None):
            if args[0] == "getprop":
                return "CPH2591\n"
            return sessions if "sessions" in args[-1] else nights

        with mock.patch.object(aw, "adb", side_effect=phone):
            self.assertEqual({"nocturne-sessions_CPH2591": 1, "nocturne-sleep_CPH2591": 0}, aw.phone_to_aw(self.client, None))
            self.assertEqual({"nocturne-sessions_CPH2591": 1, "nocturne-sleep_CPH2591": 0}, aw.phone_to_aw(self.client, None))
        bucket = FakeActivityWatch.buckets["nocturne-sessions_CPH2591"]
        self.assertEqual({"client": "nocturne", "type": "nocturne.session", "hostname": "CPH2591"}, bucket["meta"])
        self.assertEqual(1, len(bucket["events"]))

    def test_the_laptop_file_reads_afk_and_display_buckets(self):
        start = ist("2026-09-16T23:00")
        self.client.create_bucket("aw-watcher-afk_workbook", "afkstatus", "workbook", client="awatcher")
        self.client.insert("aw-watcher-afk_workbook", [event(start, start + 600_000, status="not-afk")])
        self.client.create_bucket("nocturne-display_workbook", "nocturne.display", "workbook")
        for minute in range(0, 11):
            beat = {"timestamp": aw.iso(start + minute * 60_000), "duration": 0, "data": {"backlight": 0.06, "nightLight": False}}
            self.client.heartbeat("nocturne-display_workbook", beat, pulsetime=90)
        self.assertEqual(1, len(FakeActivityWatch.buckets["nocturne-display_workbook"]["events"]))

        args = mock.Mock(host="workbook", width_mm=345, height_mm=215, min_nits=2.0, peak_nits=250.0, days=1, reach_s=90)
        with mock.patch.object(aw, "now_ms", return_value=start + 3_600_000):
            text = aw.build_laptop_file(self.client, args)
        self.assertEqual(f"span,workbook,{start},{start + 600_000},0.06,0", text.splitlines()[-1])
        self.assertEqual(f"coverage,workbook,{start + 3_600_000 - aw.DAY_MS},{start + 3_600_000}", text.splitlines()[2])

    def test_a_stretch_begun_before_the_coverage_is_left_whole_to_the_file_that_covered_its_start(self):
        # The server cuts events at a query's edges; read naively, this stretch would arrive as a second span from 23:30.
        start = ist("2026-09-16T23:00")
        self.client.create_bucket("aw-watcher-afk_workbook", "afkstatus", "workbook", client="awatcher")
        self.client.insert("aw-watcher-afk_workbook", [
            event(start, start + 3_600_000, status="not-afk"),
            event(start + 3_700_000, start + 3_800_000, status="not-afk"),
        ])
        args = mock.Mock(host="workbook", width_mm=345, height_mm=215, min_nits=2.0, peak_nits=250.0, days=1, reach_s=90)
        with mock.patch.object(aw, "now_ms", return_value=start + 30 * 60_000 + aw.DAY_MS):
            text = aw.build_laptop_file(self.client, args)
        self.assertEqual([f"span,workbook,{start + 3_700_000},{start + 3_800_000},,"], [l for l in text.splitlines() if l.startswith("span")])


if __name__ == "__main__":
    unittest.main()
