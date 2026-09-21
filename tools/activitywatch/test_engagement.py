"""Tests for engagement.py and the Wi-Fi sync helpers. From the repository: python3 -m unittest discover -s tools/activitywatch."""

from __future__ import annotations

import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest import mock

import engagement
import nocturne_aw as aw


class Engagement(unittest.TestCase):
    def test_days_run_days_opened_and_openings_over_two_weeks(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root, "indicator.json")
            for day, opened in [(date(2026, 8, 25), True), (date(2026, 9, 10), False), (date(2026, 9, 10), False),
                                (date(2026, 9, 12), True), (date(2026, 9, 12), True), (date(2026, 9, 13), False)]:
                engagement.record(day, opened, path)
            # 25 Aug is outside the 14 days ending 13 Sept (31 Aug to 13 Sept).
            self.assertEqual((3, 1, 2), engagement.summary(date(2026, 9, 13), path=path))
            self.assertEqual((0, 0, 0), engagement.summary(date(2026, 9, 13), path=Path(root, "missing.json")))


class Wifi(unittest.TestCase):
    def test_the_phone_s_wifi_address_is_read_from_ip(self):
        out = "30: wlan0: <BROADCAST,MULTICAST,UP> mtu 1500\n    inet 192.168.1.37/24 brd 192.168.1.255 scope global wlan0\n"
        self.assertEqual("192.168.1.37", aw.wlan_address(out))
        self.assertIsNone(aw.wlan_address("Device \"wlan0\" does not exist."))

    def test_sync_over_wifi_uses_the_remembered_address_and_disconnects_after(self):
        calls = []
        with tempfile.TemporaryDirectory() as root, \
                mock.patch.object(aw, "state_dir", return_value=Path(root)), \
                mock.patch.object(aw, "phone_connected", side_effect=lambda serial: serial == "192.168.1.37:5555"), \
                mock.patch.object(aw, "adb_host", side_effect=lambda *a, **k: calls.append(a) or ""), \
                mock.patch.object(aw, "sync_now", side_effect=lambda _aw, args: calls.append(("sync", args.serial))), \
                mock.patch.object(aw.subprocess, "run", side_effect=lambda cmd, **k: calls.append(tuple(cmd))):
            Path(root, "phone-wifi.json").write_text('{"address": "192.168.1.37:5555"}')
            aw.main(["sync", "--if-connected", "--wifi"])
        self.assertEqual([("connect", "192.168.1.37:5555"), ("sync", "192.168.1.37:5555"), ("adb", "disconnect", "192.168.1.37:5555")], calls)

    def test_without_a_remembered_address_a_timer_sync_does_nothing(self):
        with tempfile.TemporaryDirectory() as root, \
                mock.patch.object(aw, "state_dir", return_value=Path(root)), \
                mock.patch.object(aw, "phone_connected", return_value=False), \
                mock.patch.object(aw, "sync_now") as sync:
            aw.main(["sync", "--if-connected", "--wifi"])
        sync.assert_not_called()


if __name__ == "__main__":
    unittest.main()
