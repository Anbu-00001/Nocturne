"""Tests for power_log.py: gaps are boots, minus suspends, minus what the watcher recorded. Standard library only."""

from __future__ import annotations

import json
import unittest

import power_log as pl

MIN = 60_000
H = 60 * MIN


class Intervals(unittest.TestCase):
    def test_subtract_cuts_the_middle_the_edges_and_whole_intervals(self):
        self.assertEqual([(0, 10), (20, 100)], pl.subtract([(0, 100)], [(10, 20)]))
        self.assertEqual([(30, 100)], pl.subtract([(0, 100)], [(-5, 30)]))
        self.assertEqual([(0, 70)], pl.subtract([(0, 100)], [(70, 200)]))
        self.assertEqual([], pl.subtract([(0, 100)], [(0, 100)]))
        self.assertEqual([(0, 100)], pl.subtract([(0, 100)], []))
        self.assertEqual([(0, 10), (60, 100)], pl.subtract([(0, 100)], [(50, 60), (10, 55), (20, 30)]))
        self.assertEqual([(0, 10), (200, 210)], pl.subtract([(0, 10), (100, 150), (200, 210)], [(90, 160)]))

    def test_join_sorts_and_joins_touching_intervals_and_drops_empty_ones(self):
        self.assertEqual([(0, 30), (40, 50)], pl.join([(20, 30), (0, 10), (10, 25), (40, 50), (45, 45)]))

    def test_gaps_are_awake_time_nothing_recorded_in_stretches_worth_a_note(self):
        awake = [(0, 10 * H)]
        recorded = [(0, 2 * H), (2 * H + 5 * MIN, 6 * H)]  # a five minute hole at 2h is startup noise
        self.assertEqual([(6 * H, 10 * H)], pl.unrecorded(awake, recorded, 0, 10 * H))
        # Clipped to the coverage, and a clipped stretch under the minimum goes.
        self.assertEqual([(6 * H, 7 * H)], pl.unrecorded(awake, recorded, 0, 7 * H))
        self.assertEqual([], pl.unrecorded(awake, recorded, 0, 6 * H + 5 * MIN))

    def test_a_laptop_that_was_off_has_no_gap_however_little_was_recorded(self):
        self.assertEqual([], pl.unrecorded([], [], 0, 10 * H))
        self.assertEqual([], pl.unrecorded([(20 * H, 30 * H)], [], 0, 10 * H))


class Journal(unittest.TestCase):
    def boots_json(self):
        return json.dumps([
            {"index": -1, "boot_id": "a", "first_entry": 1_000 * 1000, "last_entry": 5_000_000 * 1000},
            {"index": 0, "boot_id": "b", "first_entry": 6_000_000 * 1000, "last_entry": 6_100_000 * 1000},
        ])

    def test_boots_come_in_milliseconds_and_the_current_one_runs_to_now(self):
        self.assertEqual([(1_000, 5_000_000), (6_000_000, 7_000_000)], pl.parse_boots(self.boots_json(), now_ms=7_000_000))
        # A clock that reads earlier than the journal's last entry never shortens the current boot.
        self.assertEqual((6_000_000, 6_100_000), pl.parse_boots(self.boots_json(), now_ms=6_050_000)[1])

    def test_suspends_pair_up_and_one_without_a_return_ends_with_its_boot(self):
        boots = [(0, 100_000), (200_000, 400_000)]
        text = "\n".join([
            "50.000000 host systemd-sleep[1]: Performing sleep operation 'suspend'...",
            "60.500000 host systemd-sleep[1]: System returned from sleep operation 'suspend'.",
            "250.000000 host systemd-sleep[2]: Performing sleep operation 'suspend'...",
            "-- some other line --",
        ])
        self.assertEqual([(50_000, 60_500), (250_000, 400_000)], pl.parse_suspends(text, boots))

    def test_a_return_without_a_start_and_a_start_after_a_start_are_handled(self):
        boots = [(0, 1_000_000)]
        text = "\n".join([
            "10.0 h systemd-sleep[1]: System returned from sleep operation 'suspend'.",
            "20.0 h systemd-sleep[1]: Performing sleep operation 'suspend'...",
            "30.0 h systemd-sleep[1]: Performing sleep operation 'hibernate'...",
            "40.0 h systemd-sleep[1]: System returned from sleep operation 'hibernate'.",
        ])
        self.assertEqual([(20_000, 1_000_000), (30_000, 40_000)], pl.parse_suspends(text, boots))

    def test_a_laptop_on_all_day_with_the_lid_shut_for_an_hour_is_awake_except_that_hour(self):
        boots = [(0, 10 * H)]
        suspends = [(4 * H, 5 * H)]
        awake = pl.awake_spans(boots, suspends)
        self.assertEqual([(0, 4 * H), (5 * H, 10 * H)], awake)
        # The watcher recorded the mornings only; the suspended hour is not a gap, the afternoon is.
        self.assertEqual([(5 * H, 10 * H)], pl.unrecorded(awake, [(0, 4 * H + 30 * MIN)], 0, 10 * H))

    def test_unreadable_journal_means_no_claim(self):
        original = pl._journalctl
        pl._journalctl = lambda args: None
        try:
            self.assertIsNone(pl.read_awake(0, 10))
        finally:
            pl._journalctl = original
        pl._journalctl = lambda args: "not json" if "--list-boots" in args else ""
        try:
            self.assertIsNone(pl.read_awake(0, 10))
        finally:
            pl._journalctl = original


if __name__ == "__main__":
    unittest.main()
