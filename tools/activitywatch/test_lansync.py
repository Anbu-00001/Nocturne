"""Tests for lansync.py and the exchange in nocturne_aw.py, over real TLS sockets on localhost.

From the repository: python3 -m unittest discover -s tools/activitywatch (standard library only; the certificate tests
need the `openssl` command, as `pair` does, and are skipped without it).
"""

from __future__ import annotations

import hashlib
import http.client
import json
import os
import shutil
import socket
import ssl
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

import lansync
import nocturne_aw as aw
from fake_activitywatch import FakeActivityWatch, FakeServer

REPO = Path(__file__).resolve().parents[2]
VECTOR = REPO / "core-model/src/test/resources/sync/vector.txt"


def vector() -> dict[str, str]:
    return dict(line.split("=", 1) for line in VECTOR.read_text().splitlines() if line and not line.startswith("#"))


class Clock:
    def __init__(self, at: int = 1_791_000_000_000):
        self.at = at

    def __call__(self) -> int:
        return self.at


class Auth(unittest.TestCase):
    def test_the_signature_is_the_one_the_phone_computes(self):
        # core-model's SyncAuthTest reads this same file; if either side changes, both tests say so.
        v = vector()
        body = bytes.fromhex(v["bodyHex"])
        self.assertEqual(v["bodySha256"], hashlib.sha256(body).hexdigest())
        self.assertEqual(v["canonicalHex"], lansync.canonical(v["device"], int(v["time"]), v["nonce"], body).hex())
        self.assertEqual(v["signature"], lansync.sign(v["secret"], v["device"], int(v["time"]), v["nonce"], body))

    def test_the_signature_covers_every_part(self):
        v = vector()
        body = bytes.fromhex(v["bodyHex"])
        args = (v["secret"], v["device"], int(v["time"]), v["nonce"], body)
        base = lansync.sign(*args)
        self.assertNotEqual(base, lansync.sign(v["secret"], "other", int(v["time"]), v["nonce"], body))
        self.assertNotEqual(base, lansync.sign(v["secret"], v["device"], int(v["time"]) + 1, v["nonce"], body))
        self.assertNotEqual(base, lansync.sign(v["secret"], v["device"], int(v["time"]), "f" * 32, body))
        self.assertNotEqual(base, lansync.sign(v["secret"], v["device"], int(v["time"]), v["nonce"], body + b"\n"))
        self.assertNotEqual(base, lansync.sign("11" * 32, v["device"], int(v["time"]), v["nonce"], body))

    def test_only_private_addresses_are_served(self):
        for ok in ("192.168.1.102", "10.0.0.5", "172.16.3.4", "172.31.255.255", "127.0.0.1", "169.254.1.1", "::1", "fe80::1%wlan0",
                   "fd12:3456::1", "::ffff:192.168.1.5"):
            self.assertTrue(lansync.is_private(ok), ok)
        for bad in ("8.8.8.8", "172.32.0.1", "203.0.113.9", "100.64.0.1", "0.0.0.0", "2001:db8::1", "2606:4700::1111", "not an address", ""):
            self.assertFalse(lansync.is_private(bad), bad)


class Body(unittest.TestCase):
    def test_the_vector_body_parses(self):
        sent, sessions, nights = lansync.parse_body(bytes.fromhex(vector()["bodyHex"]).decode())
        self.assertEqual(1791000000000, sent)
        self.assertEqual(2, sessions.count("\n"))
        self.assertTrue(nights.startswith("dateOfNight,"))
        self.assertTrue(nights.endswith("true\n"))

    def test_a_body_that_breaks_a_rule_is_refused_whole(self):
        for text in ("", "nocturne-phone,2\nsent,1\n[sessions]\n[nights]\n", "nocturne-phone,1\nsent,x\n[sessions]\n[nights]\n",
                     "nocturne-phone,1\nsent,1\n[sessions]\nh\n", "nocturne-phone,1\nsent,1\nrows\n[sessions]\n[nights]\n",
                     "nocturne-phone,1\nsent,1\n[sessions]\n[sessions]\n[nights]\n"):
            with self.assertRaises(lansync.Refused, msg=repr(text)) as caught:
                lansync.parse_body(text)
            self.assertEqual(400, caught.exception.status)
        sent, sessions, nights = lansync.parse_body("nocturne-phone,1\nsent,5\n[sessions]\n[nights]\nonly,a,header\n")
        self.assertEqual((5, "\n", "only,a,header\n"), (sent, sessions, nights))


class GuardTest(unittest.TestCase):
    def test_a_nonce_is_good_once_and_forgotten_after_ten_minutes(self):
        clock = Clock()
        guard = lansync.Guard(clock)
        self.assertTrue(guard.fresh("a" * 32))
        self.assertFalse(guard.fresh("a" * 32))
        clock.at += lansync.NONCE_KEEP_MS + 1
        self.assertTrue(guard.fresh("a" * 32))

    def test_ten_failures_in_a_minute_block_an_address_until_they_age_out(self):
        clock = Clock()
        guard = lansync.Guard(clock)
        for _ in range(lansync.FAIL_LIMIT):
            self.assertFalse(guard.blocked("192.168.1.9"))
            guard.failed("192.168.1.9")
        self.assertTrue(guard.blocked("192.168.1.9"))
        self.assertFalse(guard.blocked("192.168.1.10"))
        clock.at += lansync.FAIL_WINDOW_MS + 1
        self.assertFalse(guard.blocked("192.168.1.9"))


@unittest.skipUnless(shutil.which("openssl"), "openssl is needed to make a certificate")
class OverTls(unittest.TestCase):
    """A real server on a free port: what the phone sees is a certificate to pin and status codes to act on."""

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.identity: lansync.Identity = lansync.load_identity(Path(cls.dir.name))

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def setUp(self):
        self.clock = Clock()
        self.logs: list[str] = []
        self.calls: list[lansync.Request] = []
        self.reply = "nocturne-laptop,1\n"
        self.assertIsNotNone(self.identity)
        self.server = lansync.make_server(self.identity, self.on_exchange, bind="127.0.0.1", port=0,
                                          guard=lansync.Guard(self.clock), log=self.logs.append)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.port = self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def on_exchange(self, request, address):
        self.calls.append(request)
        if isinstance(self.reply, Exception):
            raise self.reply
        return self.reply

    def post(self, body: bytes | None = None, *, secret=None, time_ms=None, nonce=None, device="CPH2591", path=lansync.PATH, headers=None):
        body = bytes.fromhex(vector()["bodyHex"]) if body is None else body
        time_ms = self.clock.at if time_ms is None else time_ms
        nonce = nonce or os.urandom(16).hex()
        sent = {"X-Nocturne-Device": device, "X-Nocturne-Time": str(time_ms), "X-Nocturne-Nonce": nonce,
                "X-Nocturne-Auth": lansync.sign(secret or self.identity.secret, device, time_ms, nonce, body)}
        sent.update(headers or {})
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE  # what the phone does instead: compare the certificate's SHA-256 (see seen_fingerprint)
        connection = http.client.HTTPSConnection("127.0.0.1", self.port, context=context, timeout=10)
        try:
            connection.connect()
            seen = connection.sock.getpeercert(binary_form=True) if isinstance(connection.sock, ssl.SSLSocket) else None
            self.seen_fingerprint = hashlib.sha256(seen or b"").hexdigest()
            connection.request("POST", path, body=body, headers=sent)
            response = connection.getresponse()
            return response.status, response.read().decode()
        finally:
            connection.close()

    def test_a_good_exchange_returns_the_laptop_s_file_and_shows_the_certificate_to_pin(self):
        status, text = self.post()
        self.assertEqual((200, "nocturne-laptop,1\n"), (status, text))
        self.assertEqual(self.identity.fingerprint, self.seen_fingerprint)
        request = self.calls[0]
        self.assertEqual(("CPH2591", 1791000000000), (request.device, request.sent_at))
        self.assertIn("2026-10-02,1790958058078", request.nights)

    def test_each_way_of_going_wrong_gets_its_own_status_and_reaches_no_data(self):
        self.assertEqual(401, self.post(secret="22" * 32)[0])
        self.assertEqual(401, self.post(headers={"X-Nocturne-Auth": "0" * 64})[0])
        self.assertEqual(401, self.post(headers={"X-Nocturne-Auth": "\u00e9" * 64})[0], "non-ASCII is a bad signature, not a server error")
        self.assertEqual(401, self.post(headers={"X-Nocturne-Device": "someone-else"})[0])
        self.assertEqual(400, self.post(device="has spaces")[0])
        self.assertEqual(400, self.post(nonce="short")[0])
        status, text = self.post(time_ms=self.clock.at - lansync.SKEW_MS - 1)
        self.assertEqual(403, status)
        self.assertIn("clock", text)
        self.assertEqual(404, self.post(path="/v1/other")[0])
        self.assertEqual(400, self.post(b"not a body")[0])
        self.assertEqual([], self.calls)

    def test_a_request_replayed_is_refused(self):
        nonce = "ab" * 16
        self.assertEqual(200, self.post(nonce=nonce)[0])
        self.assertEqual(409, self.post(nonce=nonce)[0])
        self.assertEqual(1, len(self.calls))

    def test_too_large_a_body_is_refused_before_it_is_read(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        connection = http.client.HTTPSConnection("127.0.0.1", self.port, context=context, timeout=10)
        connection.putrequest("POST", lansync.PATH)
        connection.putheader("Content-Length", str(lansync.MAX_BODY + 1))
        connection.endheaders()
        self.assertEqual(413, connection.getresponse().status)
        connection.close()

    def test_repeated_failures_from_one_address_are_slowed_and_a_good_request_still_works_after_they_age(self):
        for _ in range(lansync.FAIL_LIMIT):
            self.assertEqual(401, self.post(secret="22" * 32)[0])
        self.assertEqual(429, self.post()[0])
        self.clock.at += lansync.FAIL_WINDOW_MS + 1
        self.assertEqual(200, self.post()[0])

    def test_the_laptop_failing_is_reported_and_does_not_end_the_server(self):
        self.reply = OSError("ActivityWatch is not running")
        status, text = self.post()
        self.assertEqual(503, status)
        self.assertIn("ActivityWatch is not running", text)
        self.reply = "nocturne-laptop,1\n"
        self.assertEqual(200, self.post()[0])

    def test_a_connection_that_says_nothing_does_not_hold_up_the_next_exchange(self):
        # Opened and left idle, as any device on the Wi-Fi could: the old design shook hands inside accept() and stopped here.
        idle = [socket.create_connection(("127.0.0.1", self.port), timeout=5) for _ in range(3)]
        try:
            started = time.monotonic()
            self.assertEqual(200, self.post()[0])
            self.assertLess(time.monotonic() - started, 5)
        finally:
            for connection in idle:
                connection.close()

    def test_a_flood_of_idle_connections_is_capped_and_the_server_recovers(self):
        flood = []
        try:
            for _ in range(lansync.MAX_CONNECTIONS + 8):
                flood.append(socket.create_connection(("127.0.0.1", self.port), timeout=5))
        finally:
            for connection in flood:
                connection.close()
        deadline = time.monotonic() + 10
        status = 0
        while time.monotonic() < deadline and status != 200:
            try:
                status = self.post()[0]
            except OSError:
                time.sleep(0.2)
        self.assertEqual(200, status)

    def test_the_identity_survives_a_restart_and_the_private_files_are_private(self):
        again = lansync.load_identity(Path(self.dir.name))
        self.assertEqual(self.identity, again)
        for name in ("key.pem", "secret.hex"):
            self.assertEqual(0o600, (Path(self.dir.name) / name).stat().st_mode & 0o777, name)
        self.assertEqual(64, len(again.secret))
        self.assertIsNone(lansync.find_identity(Path(self.dir.name) / "none"))


class Exchange(unittest.TestCase):
    """nocturne_aw's callback: the phone's rows into ActivityWatch and phone.json, the laptop's use back."""

    def setUp(self):
        self.fake = FakeServer()
        self.client = self.fake.__enter__()
        self.state = tempfile.TemporaryDirectory()
        patcher = mock.patch.dict(os.environ, {"XDG_STATE_HOME": self.state.name})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.args = mock.Mock(host="workbook", width_mm=345, height_mm=215, min_nits=2.0, peak_nits=250.0, days=1, reach_s=90)
        v = vector()
        sent, sessions, nights = lansync.parse_body(bytes.fromhex(v["bodyHex"]).decode())
        self.request = lansync.Request(v["device"], sent, sessions, nights, hashlib.sha256((sessions + "\0" + nights).encode()).hexdigest())

    def tearDown(self):
        self.fake.__exit__(None, None, None)
        self.state.cleanup()

    def test_phone_rows_go_to_activitywatch_and_phone_json_and_the_laptop_file_comes_back(self):
        on_exchange = aw.on_exchange_for(self.client, self.args, log=lambda line: None)
        with mock.patch.object(aw, "now_ms", return_value=1_791_000_100_000):
            text = on_exchange(self.request, "192.168.1.102")
        self.assertTrue(text.startswith("nocturne-laptop,1\n"))
        self.assertEqual(1, len(FakeActivityWatch.buckets["nocturne-sessions_CPH2591"]["events"]))
        self.assertEqual(1, len(FakeActivityWatch.buckets["nocturne-sleep_CPH2591"]["events"]))
        summary = json.loads((Path(self.state.name) / "nocturne/phone.json").read_text())
        self.assertEqual(("CPH2591", "2026-10-02", 34, 727), (summary["device"], summary["last_night_date"], summary["window_start_minute"], summary["window_end_minute"]))
        last = lansync.last_exchange()
        self.assertEqual(("192.168.1.102", 1, 1), (last["address"], last["sessions"], last["nights"]))
        self.assertEqual(self.request.digest, last["digest"])

    def test_an_unchanged_phone_does_not_rebuild_the_buckets_but_a_missing_bucket_is_rebuilt(self):
        on_exchange = aw.on_exchange_for(self.client, self.args, log=lambda line: None)
        on_exchange(self.request, "192.168.1.102")
        FakeActivityWatch.buckets["nocturne-sessions_CPH2591"]["events"].append({"marker": True})
        on_exchange(self.request, "192.168.1.102")
        self.assertEqual(2, len(FakeActivityWatch.buckets["nocturne-sessions_CPH2591"]["events"]), "left alone when nothing changed")
        self.client.delete_bucket("nocturne-sleep_CPH2591")
        on_exchange(self.request, "192.168.1.102")
        self.assertEqual(1, len(FakeActivityWatch.buckets["nocturne-sessions_CPH2591"]["events"]), "rebuilt once a bucket was gone")
        self.assertIn("nocturne-sleep_CPH2591", FakeActivityWatch.buckets)

    def test_the_pairing_text_is_what_the_phone_reads(self):
        identity = lansync.Identity(Path("c"), Path("k"), "ab" * 32, "cd" * 32)
        text = aw.pairing_text(identity, "workbook", "192.168.1.105", 47650)
        self.assertEqual(["nocturne-pair,1", "name,workbook", "host,workbook.local", "host,workbook", "address,192.168.1.105",
                          "port,47650", "cert," + "cd" * 32, "secret," + "ab" * 32], text.splitlines())
        self.assertNotIn("address", aw.pairing_text(identity, "workbook", None, 47650))

    def test_the_phone_s_pairing_status_reads_with_an_error_that_holds_commas(self):
        row = aw.parse_row("Row: 0 pairSequence=2, pairOutcome=ok, name=workbook, hosts=workbook.local workbook, address=192.168.1.105, "
                           "port=47650, lastAttemptAt=7, lastOkAt=5, lastResult=failed, error=502 refused, then: reset", last="error")
        self.assertEqual(("2", "ok", "workbook", "502 refused, then: reset"), (row["pairSequence"], row["pairOutcome"], row["name"], row["error"]))
        self.assertIsNone(aw.parse_row("Row: 0 pairSequence=0, pairOutcome=none, name=, error=NULL", last="error")["error"])


if __name__ == "__main__":
    unittest.main()
