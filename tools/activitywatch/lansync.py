"""Phase 5: the laptop's end of Wi-Fi sync. Standard library only; `openssl`, which Ubuntu ships, makes the certificate.

The phone always starts the conversation, so it needs no listening port: every so often, on Wi-Fi, it posts its sessions
and nights to the laptop and takes the laptop's use in the reply (one request, both directions). The laptop answers only
while it is on, which on this laptop is a few hours a day with several boots, so a missed attempt is the normal case and
the next one (about every 15 min) finds it.

Trust, set up once over adb (`nocturne_aw.py pair`):
- TLS with a self-signed certificate. The phone is given the certificate's SHA-256 and accepts nothing else, so a name
  that resolves to the wrong machine, or a stranger on the same Wi-Fi, gets no data.
- A 32-byte secret known to both. Each request carries an HMAC over its method, path, device, time, a one-use nonce and
  the body's hash, so a stranger cannot pull the laptop's use or push false sessions. Requests older than five minutes
  and nonces seen before are refused, and repeated failures from one address are slowed.
- Only addresses on a private network are served.

The wire format is in docs/PHASES.md ("Phase 5"); core-model's `SyncAuth` is the phone's copy of `sign` here, and both
read the same test vector (core-model/src/test/resources/sync/vector.txt).
"""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
import json
import re
import secrets
import ssl
import subprocess
import sys
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable

from tonight import state_dir

PROTOCOL = "nocturne-sync-v1"
PORT = 47650
PATH = "/v1/exchange"
BODY_HEADER = "nocturne-phone,1"
MAX_BODY = 16 * 1024 * 1024
SKEW_MS = 5 * 60_000
NONCE_KEEP_MS = 10 * 60_000
FAIL_LIMIT, FAIL_WINDOW_MS = 10, 60_000
MAX_CONNECTIONS = 16
HANDSHAKE_S = 10
REQUEST_S = 30
DEVICE_RE = re.compile(r"[A-Za-z0-9._-]{1,64}")
NONCE_RE = re.compile(r"[0-9a-f]{32}")


# ------------------------------------------------------------------------------------------------------------ identity

@dataclass(frozen=True)
class Identity:
    cert: Path
    key: Path
    #: 64 hex characters.
    secret: str
    #: SHA-256 of the certificate's DER, 64 hex characters: what the phone pins.
    fingerprint: str


def sync_dir() -> Path:
    return state_dir() / "sync"


def fingerprint_of(cert_pem: str) -> str:
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert_pem)).hexdigest()


def find_identity(directory: Path | None = None) -> Identity | None:
    """The laptop's certificate, key and secret, or None when `pair` or `serve` has not made them yet."""
    directory = directory or sync_dir()
    cert, key, secret_file = directory / "cert.pem", directory / "key.pem", directory / "secret.hex"
    if not (cert.exists() and key.exists() and secret_file.exists()):
        return None
    return Identity(cert, key, secret_file.read_text().strip(), fingerprint_of(cert.read_text()))


def load_identity(directory: Path | None = None) -> Identity:
    """[find_identity], making the certificate and secret first on first use."""
    directory = directory or sync_dir()
    found = find_identity(directory)
    if found:
        return found
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if not ((directory / "cert.pem").exists() and (directory / "key.pem").exists()):
        _make_certificate(directory / "cert.pem", directory / "key.pem")
    secret_file = directory / "secret.hex"
    if not secret_file.exists():
        secret_file.touch(mode=0o600)
        secret_file.write_text(secrets.token_hex(32))
    return Identity(directory / "cert.pem", directory / "key.pem", secret_file.read_text().strip(),
                    fingerprint_of((directory / "cert.pem").read_text()))


def _make_certificate(cert: Path, key: Path) -> None:
    """A P-256 key and a ten-year self-signed certificate. The phone pins the certificate, so its name does not matter."""
    key.touch(mode=0o600)
    try:
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes", "-days", "3650",
             "-subj", "/CN=nocturne-laptop", "-keyout", str(key), "-out", str(cert)],
            check=True, capture_output=True, text=True,
        )
    except FileNotFoundError:
        raise SystemExit("openssl is needed once, to make the laptop's certificate (sudo apt install openssl)")
    except subprocess.CalledProcessError as e:
        raise SystemExit(f"openssl could not make the certificate: {e.stderr.strip()}")
    key.chmod(0o600)


# --------------------------------------------------------------------------------------------------------------- auth

def canonical(device: str, time_ms: int, nonce: str, body: bytes) -> bytes:
    """What a request's HMAC covers. core-model's SyncAuth builds the same bytes."""
    return "\n".join([PROTOCOL, "POST", PATH, device, str(time_ms), nonce, hashlib.sha256(body).hexdigest()]).encode()


def sign(secret_hex: str, device: str, time_ms: int, nonce: str, body: bytes) -> str:
    return hmac.new(bytes.fromhex(secret_hex), canonical(device, time_ms, nonce, body), hashlib.sha256).hexdigest()


# Spelled out, since ipaddress's is_private also holds documentation and reserved ranges. core-model's PrivateAddress is the
# phone's copy and both tests use the same list.
PRIVATE_NETWORKS = [ipaddress.ip_network(n) for n in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8", "::1/128", "fc00::/7", "fe80::/10")]


def is_private(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address.split("%")[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return any(ip in network for network in PRIVATE_NETWORKS if network.version == ip.version)


class Refused(Exception):
    """A request that gets an error reply: [status] and a short plain-text [reason]."""

    def __init__(self, status: int, reason: str):
        super().__init__(reason)
        self.status = status
        self.reason = reason


@dataclass(frozen=True)
class Request:
    device: str
    sent_at: int
    sessions: str
    nights: str
    #: Hash of the rows, for "nothing new since the last exchange".
    digest: str


def parse_body(text: str) -> tuple[int, str, str]:
    """(sent, sessions CSV, nights CSV) of the phone's body: a header, `sent,<ms>`, then `[sessions]` and `[nights]` blocks."""
    lines = text.split("\n")
    if len(lines) < 2 or lines[0] != BODY_HEADER or not lines[1].startswith("sent,"):
        raise Refused(400, "not a nocturne-phone,1 body")
    try:
        sent = int(lines[1][5:])
    except ValueError:
        raise Refused(400, "unreadable sent time")
    blocks: dict[str, list[str]] = {}
    current: list[str] | None = None
    for line in lines[2:]:
        if line in ("[sessions]", "[nights]"):
            if line in blocks:
                raise Refused(400, f"{line} twice")
            current = blocks[line] = []
        elif current is not None:
            current.append(line)
        elif line:
            raise Refused(400, "rows before a block")
    if set(blocks) != {"[sessions]", "[nights]"}:
        raise Refused(400, "needs [sessions] and [nights]")
    return sent, "\n".join(blocks["[sessions]"]).strip("\n") + "\n", "\n".join(blocks["[nights]"]).strip("\n") + "\n"


class Guard:
    """Replay and flood protection, in memory: a restart of the server forgets it, and requests older than the skew die anyway."""

    def __init__(self, now: Callable[[], int] = lambda: int(time.time() * 1000)):
        self.now = now
        self.nonces: dict[str, int] = {}
        self.failures: dict[str, deque[int]] = defaultdict(deque)
        self.lock = threading.Lock()

    def blocked(self, address: str) -> bool:
        with self.lock:
            recent = self.failures[address]
            while recent and self.now() - recent[0] > FAIL_WINDOW_MS:
                recent.popleft()
            return len(recent) >= FAIL_LIMIT

    def failed(self, address: str) -> None:
        with self.lock:
            self.failures[address].append(self.now())

    def fresh(self, nonce: str) -> bool:
        """False for a nonce already seen; remembers this one."""
        with self.lock:
            now = self.now()
            for old in [n for n, at in self.nonces.items() if now - at > NONCE_KEEP_MS]:
                del self.nonces[old]
            if nonce in self.nonces:
                return False
            self.nonces[nonce] = now
            return True


def check(identity: Identity, guard: Guard, address: str, headers, body: bytes) -> Request:
    """Everything between a received request and its parsed rows, or the [Refused] reply that ends it."""
    if not is_private(address):
        raise Refused(403, "private networks only")
    if guard.blocked(address):
        raise Refused(429, "too many failed requests")
    device = headers.get("X-Nocturne-Device", "")
    nonce = headers.get("X-Nocturne-Nonce", "")
    auth = headers.get("X-Nocturne-Auth", "")
    try:
        time_ms = int(headers.get("X-Nocturne-Time", ""))
    except ValueError:
        time_ms = -1
    if not DEVICE_RE.fullmatch(device) or not NONCE_RE.fullmatch(nonce) or time_ms < 0:
        guard.failed(address)
        raise Refused(400, "missing or malformed Nocturne headers")
    # As bytes: compare_digest refuses a str with non-ASCII characters, which a stranger's header can hold.
    if not hmac.compare_digest(auth.encode(), sign(identity.secret, device, time_ms, nonce, body).encode()):
        guard.failed(address)
        raise Refused(401, "bad signature")
    if abs(guard.now() - time_ms) > SKEW_MS:
        raise Refused(403, "clock: the phone's and the laptop's times differ by more than five minutes")
    if not guard.fresh(nonce):
        raise Refused(409, "repeated request")
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        raise Refused(400, "body is not UTF-8")
    sent, sessions, nights = parse_body(text)
    digest = hashlib.sha256((sessions + "\0" + nights).encode()).hexdigest()
    return Request(device, sent, sessions, nights, digest)


# ------------------------------------------------------------------------------------------------------------ server

def make_server(identity: Identity, on_exchange: Callable[[Request, str], str], bind: str = "0.0.0.0", port: int = PORT,
                guard: Guard | None = None, log: Callable[[str], None] | None = None) -> ThreadingHTTPServer:
    """A TLS server whose `on_exchange(request, client address)` returns the laptop file to send back."""
    guard = guard or Guard()
    log = log or (lambda line: print(line, file=sys.stderr, flush=True))
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "nocturne"
        # Read timeout for the request line, headers and body, so a sender that stalls cannot keep a thread for ever.
        timeout = REQUEST_S

        def log_message(self, _format, *_args):
            pass

        def _reply(self, status: int, text: str) -> None:
            data = text.encode()
            self.send_response(status)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True

        def do_POST(self):  # noqa: N802
            address = self.client_address[0]
            try:
                if self.path != PATH:
                    raise Refused(404, "not found")
                length = self.headers.get("Content-Length")
                if length is None or not length.isdigit():
                    raise Refused(411, "Content-Length needed")
                if int(length) > MAX_BODY:
                    raise Refused(413, "body too large")
                body = self.rfile.read(int(length))
                request = check(identity, guard, address, self.headers, body)
                with lock:
                    reply = on_exchange(request, address)
                self._reply(200, reply)
            except Refused as e:
                if e.status != 429:  # a flood would otherwise write a line per request
                    log(f"refused {address}: {e.status} {e.reason}")
                self._reply(e.status, e.reason)
            except Exception as e:  # the laptop's side failing is not the phone's fault, and must not kill the server
                log(f"exchange with {address} failed: {type(e).__name__}: {e}")
                self._reply(503, f"the laptop could not answer: {type(e).__name__}: {e}")

        def do_GET(self):  # noqa: N802
            self._reply(404, "not found")

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(identity.cert, identity.key)
    return TlsServer((bind, port), Handler, context)


class TlsServer(ThreadingHTTPServer):
    """
    TLS is started in each connection's own thread, never in `accept()`: wrapping the listening socket would make one
    device that opens the port and says nothing hold up every later exchange (and the handshake and the body both have
    timeouts here for the same reason). At most [MAX_CONNECTIONS] are served at once; the rest are closed unanswered, and
    the phone's next attempt is 15 minutes away anyway.
    """

    daemon_threads = True

    def __init__(self, address, handler, context: ssl.SSLContext):
        super().__init__(address, handler)
        self.context = context
        self.slots = threading.BoundedSemaphore(MAX_CONNECTIONS)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            try:
                request.settimeout(HANDSHAKE_S)
                request = self.context.wrap_socket(request, server_side=True)
            except (ssl.SSLError, OSError):
                self.shutdown_request(request)
                return
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


# ------------------------------------------------------------------------------------------------------------- state

def record_exchange(address: str, request: Request, spans: int, directory: Path | None = None) -> None:
    """What `status` reports about the last exchange, and the hash that lets an unchanged phone skip the rebuild."""
    directory = directory or sync_dir()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = directory / "last.tmp"
    temp.write_text(json.dumps({
        "at": int(time.time() * 1000), "device": request.device, "address": address, "digest": request.digest,
        "sessions": request.sessions.count("\n") - 1, "nights": request.nights.count("\n") - 1, "spans": spans,
    }))
    temp.replace(directory / "last.json")


def last_exchange(directory: Path | None = None) -> dict | None:
    try:
        return json.loads(((directory or sync_dir()) / "last.json").read_text())
    except (OSError, ValueError):
        return None
