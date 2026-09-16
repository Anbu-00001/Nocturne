"""Tests for laptop_state.py. From the repository: python3 -m unittest discover -s tools/activitywatch."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from awclient import iso
from fake_activitywatch import FakeActivityWatch, FakeServer
from laptop_state import AfkTracker, Beat, backlight_share, panel_size_mm


class Presence(unittest.TestCase):
    def test_afk_after_the_timeout_and_back_at_the_next_input_as_aw_watcher_afk_does(self):
        tracker = AfkTracker(timeout_s=180)
        self.assertEqual([Beat(False, 95_000, 0.0)], tracker.update(now=100_000, idle=5_000))
        self.assertEqual([Beat(False, 100_000, 0.0), Beat(True, 100_000, 185.0)], tracker.update(now=285_000, idle=185_000))
        # A reading a millisecond off keeps the stretch's start.
        self.assertEqual([Beat(True, 100_000, 205.0)], tracker.update(now=305_000, idle=205_001))
        self.assertEqual([Beat(True, 100_000, 297.999), Beat(False, 398_000, 0.0)], tracker.update(now=400_000, idle=2_000))
        self.assertFalse(tracker.afk)
        # No input since, read a millisecond early: the not-afk stretch keeps its start too.
        self.assertEqual([Beat(False, 398_000, 0.0)], tracker.update(now=405_000, idle=7_001))

    def test_heartbeats_leave_a_not_afk_stretch_ending_at_the_last_input(self):
        with FakeServer() as client:
            client.create_bucket("aw-watcher-afk_workbook", "afkstatus", "workbook")
            tracker = AfkTracker(timeout_s=180)
            t0 = 1_789_600_000_000
            last_input = t0 + 60_000
            for now in range(t0, t0 + 600_000, 5_000):
                idle = max(0, now - min(now, last_input))
                for beat in tracker.update(now, idle):
                    status = "afk" if beat.afk else "not-afk"
                    client.heartbeat("aw-watcher-afk_workbook", {"timestamp": iso(beat.timestamp), "duration": beat.duration_s, "data": {"status": status}}, pulsetime=185)
            events = FakeActivityWatch.buckets["aw-watcher-afk_workbook"]["events"]
            self.assertEqual(["not-afk", "afk"], [e["data"]["status"] for e in events])
            self.assertEqual(iso(t0), events[0]["timestamp"])
            self.assertEqual(60.0, events[0]["duration"])
            self.assertEqual(iso(last_input), events[1]["timestamp"])
            self.assertAlmostEqual(535.0, events[1]["duration"], places=3)


class Readings(unittest.TestCase):
    def test_backlight_share_and_panel_size_from_sysfs(self):
        with tempfile.TemporaryDirectory() as root:
            device = Path(root, "backlight/intel_backlight")
            device.mkdir(parents=True)
            (device / "actual_brightness").write_text("5712\n")
            (device / "max_brightness").write_text("96000\n")
            self.assertAlmostEqual(0.0595, backlight_share(Path(root, "backlight")), places=4)

            edid = bytearray(128)
            timing = bytearray(18)
            timing[12], timing[13], timing[14] = 345 & 0xFF, 215 & 0xFF, ((345 >> 8) << 4) | (215 >> 8)
            edid[54:72] = timing
            panel = Path(root, "drm/card1-eDP-1")
            panel.mkdir(parents=True)
            (panel / "edid").write_bytes(bytes(edid))
            self.assertEqual((345, 215), panel_size_mm(Path(root, "drm")))
            self.assertIsNone(panel_size_mm(Path(root, "nothing")))


if __name__ == "__main__":
    unittest.main()
