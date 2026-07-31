#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Dmytro Klymentiev
"""
Vox Manager - cross-platform keyboard server.

Security model (v2 - mutual authentication):
- The machine holds a high-entropy 256-bit SECRET in ~/.voxmanager/config.json.
- Pairing is done with a short-lived 6-digit CODE (no QR, no camera):
  the user opens "Pair device" (tray) or runs --pair; the server shows a code
  valid for a couple of minutes. The phone POSTs the code to /pair and gets the
  secret back (one time, over the LAN). After that the code is consumed.
- Every authed request is signed with HMAC-SHA256 over
      method \\n path \\n timestamp \\n nonce \\n body
  Requests with a bad/old/replayed signature get 401 -> nobody else on the WiFi
  can type into this machine.
- The server ALSO signs its responses (HMAC over  resp \\n ts \\n nonce \\n body
  using the request's ts+nonce). The phone verifies this signature before it
  trusts the connection, so it will never type into a stranger's / old / rogue
  server that does not hold the secret. This is the key win over v1.

Run:  python voxmanager_server.py            # tray, headless
      python voxmanager_server.py --console  # console + auto-show a pairing code
      python voxmanager_server.py --pair     # print a pairing code and exit
      python voxmanager_server.py --reset    # new secret (all phones must re-pair)
"""

import argparse
import base64
import faulthandler
import hashlib
import hmac
import http.server
import json
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
from pathlib import Path

try:
    from pynput.keyboard import Controller, Key
except ImportError:
    print("Missing dependency: pip install pynput")
    raise

# Pairing v3 key exchange (encrypts the secret in transit). Required — no cleartext
# fallback (a missing dep should fail loud, not silently downgrade security).
try:
    from cryptography.hazmat.primitives.asymmetric.x25519 import (
        X25519PrivateKey, X25519PublicKey)
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
except ImportError:
    print("Missing dependency: pip install cryptography")
    raise

# Brand tokens (colors / names) — single source of truth is brand/tokens.json,
# generated into pc_server/brand_tokens.py. Fall back to inline defaults if the
# generated module is missing so the server still runs.
try:
    from brand_tokens import C as BRAND, GRADIENT_BRAND, NAME as APP_NAME, FONT, FONT_SEMIBOLD, FONT_MONO
except Exception:                                          # pragma: no cover
    class BRAND:
        BG = "#07060F"; SURFACE = "#15132B"; SURFACE_2 = "#1E1B3A"
        INK = "#ECECF7"; MUTED = "#9A9AB5"; FAINT = "#5E5E7C"
        ACCENT = "#8B5CF6"; ACCENT_2 = "#34D399"
    GRADIENT_BRAND = ("#8B5CF6", "#34D399")
    APP_NAME = "Vox Manager"
    FONT, FONT_SEMIBOLD, FONT_MONO = "Segoe UI", "Segoe UI Semibold", "Consolas"


def set_app_user_model_id():
    """Tell Windows our app identity so the taskbar/notifications say 'Vox Manager'
    instead of the raw exe name. No-op off Windows."""
    if not sys.platform.startswith("win"):
        return
    try:
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("com.voxmanager.server")
    except Exception:
        pass


def set_dpi_awareness():
    """Become DPI-aware so Windows renders our windows at native pixels instead of
    bitmap-stretching them (which makes text/code/icon look blurry/blocky on a
    125%/150% display). Call once, before any window is created. No-op off Windows."""
    if not sys.platform.startswith("win"):
        return
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(2)  # PER_MONITOR_DPI_AWARE
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass

PORT = 8765

# Version + auto-update. APP_VERSION is the single source of truth for the built
# server; release.ps1 verifies it matches the release tag and publishes a manifest.
APP_VERSION = "1.3.0"
UPDATE_MANIFEST_URL = "https://voxmanager.com/latest.json"  # {version,url,sha256,notes}
APP_URL = "https://play.google.com/store/apps/details?id=com.voxmanager"  # phone app; QR in the panel (placeholder until published)
SERVER_PORT = PORT  # actual bound port, set at startup; shown in the info tab
UPDATE_INFO = None  # populated by check_for_update() when a newer build is published

# v2 uses a SEPARATE, fixed discovery port (8767) so it can coexist with a v1
# server still squatting UDP 8766. The TCP port still auto-falls-back if 8765 is
# taken; discovery must stay a well-known port so the phone knows where to ask.
DISCOVERY_PORT = 8767
DISCOVERY_MAGIC = "VOXMANAGER_DISCOVER"
DISCOVERY_RESPONSE = "VOXMANAGER_SERVER"

CONFIG_DIR = Path.home() / ".voxmanager"
CONFIG_FILE = CONFIG_DIR / "config.json"
CRASH_LOG = CONFIG_DIR / "crash.log"
_crash_log_handle = None  # kept alive for the process lifetime, see _enable_crash_log


def _enable_crash_log():
    """Write a stack trace somewhere that survives, for every way this can die.

    The tray build is --noconsole, so stdout and stderr go nowhere: a traceback, or
    a hard abort inside a C extension, leaves nothing behind except a bare entry in
    the Windows event log naming a DLL and an offset. That is not enough to fix
    anything. faulthandler covers the C-level aborts (a Tcl_Panic from tkinter shows
    up here), the two excepthooks cover ordinary Python exceptions on the main and
    on worker threads.

    The file lives in CONFIG_DIR rather than next to the script: under PyInstaller
    --onefile, __file__ resolves inside the extracted _MEIxxxx temp directory, which
    is wiped when the process exits, so anything written there is lost precisely
    when it is needed.
    """
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        # Kept open for the lifetime of the process on purpose: faulthandler writes
        # to this descriptor from a signal handler and must not reopen it there.
        f = open(CRASH_LOG, "a", encoding="utf-8", buffering=1)
        f.write(f"\n=== started {time.strftime('%Y-%m-%d %H:%M:%S')} "
                f"v{APP_VERSION} pid {os.getpid()} ===\n")
        faulthandler.enable(file=f, all_threads=True)

        def _hook(exc_type, exc, tb):
            f.write(f"--- unhandled {time.strftime('%H:%M:%S')} ---\n")
            traceback.print_exception(exc_type, exc, tb, file=f)

        sys.excepthook = _hook
        threading.excepthook = lambda a: _hook(a.exc_type, a.exc_value, a.exc_traceback)
        return f
    except Exception:
        return None  # diagnostics must never be the reason the server won't start

# Freshness window (ms) for the client timestamp, enforced against the server's OWN
# clock. Kept tight: the phone first calls the unsigned GET /time beacon, learns the
# server-clock offset, and stamps every signed request in server time (see the Kotlin
# client's ensureTimeSynced). So drift no longer needs a huge window, and a captured
# request stays replayable for at most this long even across a restart (which clears
# the in-memory nonce set). This is also the horizon for remembering nonces.
CLOCK_SKEW_MS = 2 * 60 * 1000  # 2 min
# Pairing code lifetimes.
PAIR_TTL_TRAY = 120        # seconds, GUI pairing
PAIR_TTL_CONSOLE = 600     # seconds, debugging
PAIR_MAX_ATTEMPTS = 5      # wrong tries before a code is burned

HOSTNAME = socket.gethostname()
keyboard = Controller()

SPECIAL_KEYS = {
    "ENTER": Key.enter,
    "BACKSPACE": Key.backspace,
    "TAB": Key.tab,
    "ESCAPE": Key.esc,
    "DELETE": Key.delete,
    "UP": Key.up,
    "DOWN": Key.down,
    "LEFT": Key.left,
    "RIGHT": Key.right,
    "HOME": Key.home,
    "END": Key.end,
}


# ---------------------------------------------------------------- secret / config

def _harden_perms(path):
    """Restrict the secret file to its owner (POSIX 0600). No-op on Windows, where
    the user-profile NTFS ACL already keeps it private."""
    if sys.platform.startswith("win"):
        return
    try:
        os.chmod(path, 0o600)
    except Exception:
        pass


def load_or_create_secret(reset=False):
    """256-bit hex secret, shared by every phone paired with this machine."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if not reset and CONFIG_FILE.exists():
        try:
            cfg = json.loads(CONFIG_FILE.read_text())
            sec = cfg.get("secret")
            if sec:
                _harden_perms(CONFIG_FILE)  # tighten legacy files written loosely
                return sec
        except Exception:
            pass
    sec = secrets.token_hex(32)  # 64 hex chars = 256 bits
    CONFIG_FILE.write_text(json.dumps({"secret": sec}))
    _harden_perms(CONFIG_FILE)
    return sec


# ---------------------------------------------------------------- crypto helpers

def sign(secret_hex, message):
    """HMAC-SHA256(secret, message) -> lowercase hex. Matches the Kotlin client."""
    key = bytes.fromhex(secret_hex)
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).hexdigest()


# ---- transport encryption: post-pairing payloads are ENCRYPTED, not just signed.
# A per-link AES-256-GCM key is derived from the shared secret via HKDF-SHA256; each
# message body is sealed as base64(nonce[12] || ciphertext+tag). The existing HMAC
# envelope still signs the (encrypted) body, so auth + replay protection are unchanged
# and a passive Wi-Fi sniffer no longer sees the dictated text. Mirrored byte-for-byte
# by the Kotlin client.
TRANSPORT_HKDF_SALT = b"voxmanager-transport-v1"


def derive_transport_key(secret_hex):
    return HKDF(algorithm=hashes.SHA256(), length=32,
                salt=TRANSPORT_HKDF_SALT, info=b"").derive(bytes.fromhex(secret_hex))


def encrypt_payload(key, plaintext):
    nonce = secrets.token_bytes(12)
    ct = AESGCM(key).encrypt(nonce, plaintext.encode("utf-8"), None)
    return base64.b64encode(nonce + ct).decode("ascii")


def decrypt_payload(key, blob):
    raw = base64.b64decode(blob)
    return AESGCM(key).decrypt(raw[:12], raw[12:], None).decode("utf-8")


# ---- pairing key exchange (v3): the secret is delivered ENCRYPTED, never in clear.
# The phone sends an ephemeral X25519 public key with the 6-digit code; the server
# does X25519 ECDH against it, derives an AES-256-GCM key via HKDF-SHA256 (the code
# mixed into the HKDF info), and returns the secret sealed under it. A passive Wi-Fi
# sniffer sees only public keys + ciphertext and cannot recover the secret. The exact
# wire format below is mirrored byte-for-byte by the Kotlin client (BouncyCastle).
PAIR_HKDF_SALT = b"voxmanager-pair-v3"


def seal_secret_for_phone(secret_hex, code, phone_pub_hex):
    """Wrap `secret_hex` for the phone owning `phone_pub_hex`. Returns hex fields
    {v, server_pub, nonce, ct}. Raises on malformed input."""
    phone_pub = X25519PublicKey.from_public_bytes(bytes.fromhex(phone_pub_hex))
    server_priv = X25519PrivateKey.generate()
    server_pub = server_priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    shared = server_priv.exchange(phone_pub)
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=PAIR_HKDF_SALT,
               info=b"secret-wrap|" + code.encode("utf-8")).derive(shared)
    nonce = secrets.token_bytes(12)
    aad = bytes.fromhex(phone_pub_hex) + server_pub          # AAD = phone_pub || server_pub
    ct = AESGCM(key).encrypt(nonce, secret_hex.encode("utf-8"), aad)  # ct includes the 16B tag
    return {"v": 3, "server_pub": server_pub.hex(), "nonce": nonce.hex(), "ct": ct.hex()}


# Replay guard: remember recently seen nonces and drop duplicates.
_seen_nonces = {}
_nonce_lock = threading.Lock()


def nonce_is_fresh(nonce, now_ms):
    with _nonce_lock:
        # prune
        stale = [n for n, t in _seen_nonces.items() if now_ms - t > CLOCK_SKEW_MS]
        for n in stale:
            _seen_nonces.pop(n, None)
        if nonce in _seen_nonces:
            return False
        _seen_nonces[nonce] = now_ms
        return True


# ---------------------------------------------------------------- pairing state

class Pairing:
    def __init__(self):
        self.code = None
        self.expiry = 0.0
        self.attempts = 0
        # Set True the moment a phone pairs successfully, so the pairing window can
        # close itself (no point showing a code once a device has it). Reset on open().
        self.consumed = False
        self.lock = threading.Lock()

    def open(self, ttl):
        code = f"{secrets.randbelow(1_000_000):06d}"
        with self.lock:
            self.code = code
            self.expiry = time.time() + ttl
            self.attempts = 0
            self.consumed = False
        return code

    def try_code(self, candidate):
        """Return True once for the right code, then burn it."""
        with self.lock:
            if not self.code or time.time() > self.expiry:
                return None  # no active pairing
            if self.attempts >= PAIR_MAX_ATTEMPTS:
                self.code = None
                return None
            if hmac.compare_digest(str(candidate), self.code):
                self.code = None       # one-time use
                self.consumed = True   # signal the pairing window to close
                return True
            self.attempts += 1
            return False


PAIRING = Pairing()

# Throttle the unauthenticated /request-pair trigger so a LAN peer can't spam the
# pairing window onto the screen. Always answers 200 (throttling stays invisible).
REQUEST_PAIR_MIN_INTERVAL = 3.0  # seconds between accepted triggers
_last_pair_window = 0.0
_pair_window_lock = threading.Lock()


# ---------------------------------------------------------------- typing

def _paste_hotkey():
    # Windows: use Win32 keybd_event for a reliable Ctrl+V. pynput's press/release
    # sometimes lets the app see a bare "v" (the modifier doesn't register in time).
    if sys.platform.startswith("win"):
        try:
            import win32api, win32con
            win32api.keybd_event(win32con.VK_CONTROL, 0, 0, 0)
            time.sleep(0.02)
            win32api.keybd_event(ord("V"), 0, 0, 0)
            time.sleep(0.02)
            win32api.keybd_event(ord("V"), 0, win32con.KEYEVENTF_KEYUP, 0)
            win32api.keybd_event(win32con.VK_CONTROL, 0, win32con.KEYEVENTF_KEYUP, 0)
            return
        except Exception as e:
            print(f"  [type] win32 paste failed -> pynput: {e}")
    mod = Key.cmd if sys.platform == "darwin" else Key.ctrl
    keyboard.press(mod)
    time.sleep(0.02)
    keyboard.press("v")
    time.sleep(0.02)
    keyboard.release("v")
    keyboard.release(mod)


def _clipboard_put(text, timeout=0.6):
    """Put `text` on the clipboard and CONFIRM it landed by reading it back.

    pyperclip.copy() can return before (or without) the data actually being set:
    clipboard managers and apps like browsers momentarily lock the clipboard, so a
    single copy + fixed sleep is a race. If we then paste, Ctrl+V grabs whatever stale
    content was there before -- the exact "it inserts my old clipboard, not the voice
    text" bug. So we retry the copy and verify the read-back equals our text, and the
    caller only pastes when this returns True."""
    import pyperclip
    deadline = time.time() + timeout
    while True:
        try:
            pyperclip.copy(text)
        except Exception:
            pass
        try:
            if pyperclip.paste() == text:
                return True
        except Exception:
            pass
        if time.time() >= deadline:
            return False
        time.sleep(0.02)


def _paste_via_clipboard(text):
    """Insert text by placing it on the clipboard and pasting (Ctrl/Cmd+V). Carries
    real Unicode, so it is independent of the active keyboard layout. Used on
    macOS/Linux and as a Windows fallback. The previous clipboard is saved and
    restored, and we only paste once the write is CONFIRMED (see _clipboard_put),
    otherwise Ctrl+V would insert whatever was on the clipboard before."""
    import pyperclip
    try:
        saved = pyperclip.paste()
    except Exception:
        saved = None
    if not _clipboard_put(text):
        raise RuntimeError("clipboard write could not be confirmed")
    _paste_hotkey()
    time.sleep(0.15)              # let the (possibly slow) app consume the paste before we restore
    if saved is not None and saved != text:
        try:
            pyperclip.copy(saved)
        except Exception:
            pass


# ---- Windows: inject real Unicode via SendInput (no clipboard) ------------
# KEYEVENTF_UNICODE delivers the literal code point to the focused control, bypassing
# the keyboard layout entirely. This fixes layout-dependent garbling (e.g. Cyrillic
# under an English layout) AND avoids touching the user's clipboard. The whole string
# goes in one SendInput call, so it is atomic and apps cannot drop/reorder characters.
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
VK_RETURN = 0x0D

# Struct defs are pure ctypes (harmless off-Windows); only the SendInput call is Win32.
import ctypes
from ctypes import wintypes
_ULONG_PTR = ctypes.c_size_t  # pointer-sized, matches ULONG_PTR on 32/64-bit


class _KEYBDINPUT(ctypes.Structure):
    _fields_ = (("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("dwExtraInfo", _ULONG_PTR))


class _MOUSEINPUT(ctypes.Structure):  # only here so the union has the correct size
    _fields_ = (("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", _ULONG_PTR))


class _INPUTUNION(ctypes.Union):
    _fields_ = (("ki", _KEYBDINPUT), ("mi", _MOUSEINPUT))


class _INPUT(ctypes.Structure):
    _fields_ = (("type", wintypes.DWORD), ("u", _INPUTUNION))


def _build_unicode_inputs(text):
    """Build the SendInput array for `text`: a key-down + key-up Unicode event per
    UTF-16 unit. Newlines become a real Enter key; characters outside the BMP (e.g.
    emoji) are split into their surrogate pair."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    events = []

    def add(wVk, wScan, flags):
        ki = _KEYBDINPUT(wVk, wScan, flags, 0, 0)
        events.append(_INPUT(INPUT_KEYBOARD, _INPUTUNION(ki=ki)))

    for ch in text:
        if ch == "\n":
            add(VK_RETURN, 0, 0)
            add(VK_RETURN, 0, KEYEVENTF_KEYUP)
            continue
        cp = ord(ch)
        if cp > 0xFFFF:                       # non-BMP -> UTF-16 surrogate pair
            cp -= 0x10000
            units = (0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF))
        else:
            units = (cp,)
        for u in units:
            add(0, u, KEYEVENTF_UNICODE)
            add(0, u, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP)
    return events


def _type_unicode_win(text):
    events = _build_unicode_inputs(text)
    if not events:
        return
    user32 = ctypes.windll.user32
    n = len(events)
    arr = (_INPUT * n)(*events)
    sent = user32.SendInput(n, arr, ctypes.sizeof(_INPUT))
    if sent != n:
        raise ctypes.WinError(ctypes.get_last_error())


# Beside the secret, not beside the script: under PyInstaller --onefile the script
# lives in a temp directory that is wiped on exit, so this log used to disappear
# exactly when something had gone wrong.
_TYPE_LOG = str(CONFIG_DIR / "type.log")


def _type_log(msg):
    """Append a typing-path event to a file (stdout isn't captured for the tray
    process). Lets us see which path ran and why SendInput failed, if it did."""
    try:
        with open(_TYPE_LOG, "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%H:%M:%S')} {msg}\n")
    except Exception:
        pass


def type_text(text):
    """Insert recognised text into the focused app, layout-independently.

    Windows: inject the literal Unicode via SendInput (no clipboard touched), with a
    retry, then per-char typing as a last resort. The clipboard is deliberately NOT
    used here -- a Ctrl+V fallback is what could paste the user's previous clipboard
    (a copied document) instead of the dictated text.
    macOS/Linux: clipboard paste (layout-independent), per-char typing if unavailable."""
    if not text:
        return
    if sys.platform.startswith("win"):
        for attempt in range(2):
            try:
                _type_unicode_win(text)
                return
            except Exception as e:
                _type_log(f"sendinput attempt {attempt} failed: {e!r}")
                time.sleep(0.03)
        _type_log("sendinput gave up -> char typing")
        for ch in text:
            keyboard.type(ch)
            time.sleep(0.008)
        return
    try:
        _paste_via_clipboard(text)
    except Exception as e:
        # Last resort: paced per-char typing (layout-bound, may garble non-Latin).
        _type_log(f"clipboard failed -> char typing: {e!r}")
        for ch in text:
            keyboard.type(ch)
            time.sleep(0.008)


def send_key(name):
    key = SPECIAL_KEYS.get(name.upper())
    if key is None:
        return False
    keyboard.press(key)
    keyboard.release(key)
    return True


# ---------------------------------------------------------------- networking

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip


def active_window_title():
    """Best-effort title of the window that currently has keyboard focus — i.e. where
    dictated text will land. Surfaced in the signed status so the phone can show the
    user WHERE their words will be typed. Windows only for now; "" elsewhere."""
    if not sys.platform.startswith("win"):
        return ""
    try:
        import ctypes
        hwnd = ctypes.windll.user32.GetForegroundWindow()
        n = ctypes.windll.user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(n + 1)
        ctypes.windll.user32.GetWindowTextW(hwnd, buf, n + 1)
        return buf.value or ""
    except Exception:
        return ""


class ExclusiveHTTPServer(http.server.ThreadingHTTPServer):
    # Do NOT reuse an in-use address: if the port is taken we want bind() to fail
    # so we can fall back to a free port instead of silently sharing it.
    allow_reuse_address = False
    # Threaded on purpose: a single-threaded server is trivially wedged by any peer
    # on the Wi-Fi that opens a connection and then stalls (announce a body, send no
    # bytes) — dictation would stop for everyone until that socket times out. One
    # thread per connection means a stalled peer only burns its own thread, which the
    # per-connection timeout below reaps.
    daemon_threads = True

    def handle_error(self, request, client_address):
        # A peer that drops mid-request or stalls past the timeout is normal on a LAN
        # and must not spew tracebacks (console mode) or grow the tray log forever.
        pass


# Typing is a shared, stateful resource (the focused window, the clipboard), so the
# threaded server must serialise it — two concurrent requests would otherwise
# interleave their characters into one garbled line.
_type_lock = threading.Lock()

# Ceiling on a request body. Dictated phrases are bytes, not megabytes; the cap is
# what stops an unauthenticated peer from making us allocate and read at will (the
# body is necessarily read BEFORE the signature over it can be checked).
MAX_BODY_BYTES = 1 << 20  # 1 MiB


def make_handler(secret):
    tkey = derive_transport_key(secret)  # per-link AES-GCM key for payload encryption

    class Handler(http.server.BaseHTTPRequestHandler):

        # Reap a connection that opens and then says nothing (or dribbles its body),
        # instead of parking a thread on it forever. Generous next to a LAN round trip.
        timeout = 15

        # -- low level senders --------------------------------------------------

        def _send_plain(self, code, obj):
            """Unsigned + UNENCRYPTED response (pre-pairing / 401 / errors only)."""
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

        def _send_signed(self, code, obj, req_ts, req_nonce):
            """Signed + ENCRYPTED response so the phone proves it reached the right PC
            and a sniffer can't read the payload."""
            body = encrypt_payload(tkey, json.dumps(obj))
            resp_sig = sign(secret, "\n".join(["resp", req_ts, req_nonce, body]))
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-Sig", resp_sig)
            self.end_headers()
            self.wfile.write(body.encode())

        # -- request body -------------------------------------------------------

        def _read_body(self):
            """Read the request body, or answer an error and return None.

            Runs before any authentication (the signature covers the body, so the
            body has to exist first), which is exactly why it refuses a missing,
            malformed or oversized Content-Length instead of trusting it."""
            raw = self.headers.get("Content-Length", "0")
            try:
                length = int(raw)
            except (TypeError, ValueError):
                self._send_plain(400, {"error": "bad content-length"})
                return None
            if length < 0:
                self._send_plain(400, {"error": "bad content-length"})
                return None
            if length > MAX_BODY_BYTES:
                self._send_plain(413, {"error": "body too large"})
                return None
            if not length:
                return ""
            data = self.rfile.read(length)
            if len(data) != length:            # client hung up mid-body
                return None                    # connection is done; nothing to answer
            return data.decode("utf-8", "replace")

        # -- auth ---------------------------------------------------------------

        def _verify(self, body):
            """Return (ts, nonce) if the request signature is valid, else None."""
            ts = self.headers.get("X-Ts", "")
            nonce = self.headers.get("X-Nonce", "")
            sig = self.headers.get("X-Sig", "")
            if not ts or not nonce or not sig:
                return None
            try:
                ts_i = int(ts)
            except ValueError:
                return None
            now_ms = int(time.time() * 1000)
            if abs(now_ms - ts_i) > CLOCK_SKEW_MS:
                return None
            expected = sign(secret, "\n".join([self.command, self.path, ts, nonce, body]))
            if not hmac.compare_digest(expected, sig):
                return None
            if not nonce_is_fresh(nonce, now_ms):
                return None  # replay
            return ts, nonce

        # -- routes -------------------------------------------------------------

        def do_OPTIONS(self):
            self._send_plain(200, {"ok": True})

        def do_GET(self):
            # Unsigned clock beacon: lets the phone compute the server-clock offset so
            # it can stamp signed requests in server time (keeps the replay window
            # tight). Returns only the time; nothing secret here.
            if self.path == "/time":
                return self._send_plain(200, {"server_ms": int(time.time() * 1000)})
            body = ""
            auth = self._verify(body)
            if not auth:
                return self._send_plain(401, {"error": "unauthorized"})
            ts, nonce = auth
            self._send_signed(200, {"status": "ok", "hostname": HOSTNAME,
                                    "active_window": active_window_title()}, ts, nonce)

        def do_POST(self):
            body = self._read_body()
            if body is None:
                return

            # Phone asks us to surface a pairing code (unsigned, pre-pairing).
            # This only pops the code window — the secret is still gated behind the
            # 6-digit code, so an unauthenticated trigger cannot leak anything.
            if self.path == "/request-pair":
                global _last_pair_window
                now = time.time()
                with _pair_window_lock:
                    fresh = now - _last_pair_window >= REQUEST_PAIR_MIN_INTERVAL
                    if fresh:
                        _last_pair_window = now
                if fresh:
                    try:
                        open_pair_window()
                    except Exception:
                        pass
                return self._send_plain(200, {"status": "ok"})

            # Pairing is authorised by the 6-digit code, not by a signature.
            if self.path == "/pair":
                return self._handle_pair(body)

            auth = self._verify(body)
            if not auth:
                return self._send_plain(401, {"error": "unauthorized"})
            ts, nonce = auth

            # The signed body is encrypted (base64 nonce||ct); decrypt before parsing.
            try:
                plain = decrypt_payload(tkey, body) if body else ""
            except Exception:
                return self._send_signed(400, {"error": "bad ciphertext"}, ts, nonce)

            try:
                data = json.loads(plain) if plain else {}
            except json.JSONDecodeError:
                return self._send_signed(400, {"error": "bad json"}, ts, nonce)

            text = data.get("text", "")
            key = data.get("key", "")
            if key:
                with _type_lock:
                    ok = send_key(key)
                self._send_signed(200 if ok else 400,
                                  {"status": "ok" if ok else "error", "key": key},
                                  ts, nonce)
            elif text:
                with _type_lock:
                    type_text(text)
                self._send_signed(200, {"status": "ok"}, ts, nonce)
            else:
                self._send_signed(400, {"error": "no text or key"}, ts, nonce)

        def _handle_pair(self, body):
            try:
                data = json.loads(body) if body else {}
            except json.JSONDecodeError:
                return self._send_plain(400, {"error": "bad json"})
            # v3 requires the phone's ephemeral key — there is NO cleartext path.
            phone_pub = data.get("eph_pub", "")
            if not phone_pub:
                return self._send_plain(400, {"error": "pairing upgrade required"})
            code = data.get("code", "")
            result = PAIRING.try_code(code)
            if result is None:
                return self._send_plain(403, {"error": "no active pairing"})
            if result is False:
                return self._send_plain(403, {"error": "bad code"})
            # Right code -> hand the secret over ONCE, sealed to the phone's key.
            try:
                sealed = seal_secret_for_phone(secret, code, phone_pub)
            except Exception:
                return self._send_plain(400, {"error": "bad key exchange"})
            sealed["hostname"] = HOSTNAME
            self._send_plain(200, sealed)

        def log_message(self, *args):
            pass

    return Handler


def run_discovery_server(http_port):
    """Answer phone broadcasts with our IP + actual HTTP port + hostname."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", DISCOVERY_PORT))
    except OSError as e:
        print(f"  [discovery] could not bind UDP {DISCOVERY_PORT}: {e}")
        return
    while True:
        try:
            data, addr = sock.recvfrom(1024)
            if data.decode("utf-8").strip() == DISCOVERY_MAGIC:
                ip = get_local_ip()
                resp = f"{DISCOVERY_RESPONSE}:{ip}:{http_port}:{HOSTNAME}"
                sock.sendto(resp.encode(), addr)
        except Exception:
            pass


# ---------------------------------------------------------------- pairing UI

def show_pair_code(code, ttl, icon=None):
    """Surface the 6-digit code: tray balloon if possible, else a file."""
    minutes = max(1, ttl // 60)
    msg = f"Pairing code: {code}\nEnter it in the app within {minutes} min."
    if icon is not None:
        try:
            icon.notify(msg, "Vox Manager")
            return
        except Exception:
            pass
    # Fallback: write to a file and open it so the user can read the code.
    try:
        p = CONFIG_DIR / "pairing_code.txt"
        p.write_text(msg)
        open_file(p)
    except Exception:
        print(msg)


def open_file(path):
    path = str(path)
    if sys.platform.startswith("win"):
        os.startfile(path)  # noqa
    elif sys.platform == "darwin":
        subprocess.run(["open", path])
    else:
        subprocess.run(["xdg-open", path])


# ---------------------------------------------------------------- tray UI

def _hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def make_tray_image():
    """Tray icon = the gradient mic WITHOUT the tile, filling the height (no frame,
    bigger glyph — reads better at tiny tray sizes)."""
    return make_mic(64, tile=False)


def start_background(secret, preferred_port):
    try:
        server = ExclusiveHTTPServer(("0.0.0.0", preferred_port), make_handler(secret))
    except OSError:
        server = ExclusiveHTTPServer(("0.0.0.0", 0), make_handler(secret))
    port = server.server_address[1]
    threading.Thread(target=run_discovery_server, args=(port,), daemon=True).start()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, port


# ---------------------------------------------------------------- rendered UI
# The pairing window content is drawn as one anti-aliased PIL image (supersampled
# x3, LANCZOS down) instead of native Tk widgets — real Segoe UI type, a smooth
# microphone and a gradient pill button, so it reads like a modern app, not a
# Tk dialog. Only the dynamic bits (code + countdown) re-render each second.

_RENDER_SCALE = 3
WIN_W, WIN_H = 340, 334
# Two type sizes only — hierarchy is carried by weight + color, not size.
FONT_BODY = 13       # title, subtitle, countdown, button
FONT_DISPLAY = 32    # the 6-digit code (the one hero element)
_font_cache = {}


def _font(weight, size):
    """Load Segoe UI (Windows) at a pixel size, with graceful fallbacks."""
    from PIL import ImageFont
    key = (weight, size)
    if key in _font_cache:
        return _font_cache[key]
    candidates = {
        "regular":  ["segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"],
        "semibold": ["seguisb.ttf", "segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"],
        "bold":     ["segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"],
    }[weight]
    bases = [r"C:\Windows\Fonts", "/Library/Fonts",
             "/usr/share/fonts/truetype/dejavu", "/usr/share/fonts"]
    font = None
    for name in candidates:
        for base in bases:
            try:
                font = ImageFont.truetype(os.path.join(base, name), size)
                break
            except Exception:
                continue
        if font:
            break
    if font is None:
        try:
            font = ImageFont.truetype("DejaVuSans.ttf", size)
        except Exception:
            font = ImageFont.load_default()
    _font_cache[key] = font
    return font


def make_mic(size, tile=True, pad_frac=0.08):
    """Aurora-Mic rendered crisply at `size` px. Geometry mirrors the master
    brand/icon/aurora-mic.svg (512 viewport). Round stroke caps like the SVG.

    tile=True  -> gradient mic on the dark rounded brand tile (app/launcher form).
    tile=False -> just the gradient mic, full height, transparent background
                  (used for the system-tray icon — no frame, bigger glyph)."""
    from PIL import Image, ImageDraw
    s = int(size)
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Mic geometry (512-ref). The cradle sits BELOW the capsule with a clear gap
    # (capsule bottom 296 -> cradle well below), so they never merge into one blob.
    CAP_TOP, MIC_BOT = 120, 416
    if tile:
        sc = s / 512.0
        d.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(round(120 * sc)),
                            fill=_hex_rgb(BRAND.SURFACE) + (255,))
        # centre the mic vertically in the tile (its centre is 268, tile centre 256)
        oy = 256.0 - (CAP_TOP + MIC_BOT) / 2.0  # = -12

        def T(x, y):
            return (x * sc, (y + oy) * sc)
    else:
        # Scale the mic's bounding box (CAP_TOP..MIC_BOT) to fill the canvas height.
        sc = s * (1.0 - 2.0 * pad_frac) / (MIC_BOT - CAP_TOP)
        mcx, mcy = 256.0, (CAP_TOP + MIC_BOT) / 2.0  # mic centre in 512-ref space

        def T(x, y):
            return ((x - mcx) * sc + s / 2.0, (y - mcy) * sc + s / 2.0)

    w = max(1, int(round(34 * sc)))
    cap = w / 2.0

    # vertical gradient mapped over the mic's full extent (matches the SVG)
    gy0, gy1 = T(0, CAP_TOP)[1], T(0, MIC_BOT)[1]
    grad = Image.new("RGB", (s, s))
    gd = ImageDraw.Draw(grad)
    top, bot = _hex_rgb(GRADIENT_BRAND[0]), _hex_rgb(GRADIENT_BRAND[1])
    for y in range(s):
        t = 0.0 if gy1 == gy0 else min(1.0, max(0.0, (y - gy0) / (gy1 - gy0)))
        gd.line([(0, y), (s, y)], fill=tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)))

    mask = Image.new("L", (s, s), 0)
    m = ImageDraw.Draw(mask)
    a, b = T(208, 120), T(304, 296)                       # capsule (pill), bottom y296
    m.rounded_rectangle((a[0], a[1], b[0], b[1]), radius=int(round(48 * sc)), fill=255)
    a, b = T(168, 184), T(344, 360)                       # cradle U: centre (256,272) r88
    m.arc((a[0], a[1], b[0], b[1]), start=0, end=180, fill=255, width=w)
    a, b = T(256, 360), T(256, 416)                       # stem
    m.line((a[0], a[1], b[0], b[1]), fill=255, width=w)
    a, b = T(196, 416), T(316, 416)                       # base
    m.line((a[0], a[1], b[0], b[1]), fill=255, width=w)
    for cxp, cyp in ((168, 272), (344, 272), (256, 416), (196, 416), (316, 416)):
        x, y = T(cxp, cyp)                                # round caps
        m.ellipse((x - cap, y - cap, x + cap, y + cap), fill=255)

    img.paste(grad, (0, 0), mask)
    return img


_QR_CACHE = {}


def _get_qr(px):
    """Black-on-white QR of APP_URL at px size (cached). None if qrcode is missing."""
    if px in _QR_CACHE:
        return _QR_CACHE[px]
    try:
        import qrcode
        from PIL import Image
        qr = qrcode.QRCode(border=2, box_size=10,
                           error_correction=qrcode.constants.ERROR_CORRECT_M)
        qr.add_data(APP_URL)
        qr.make(fit=True)
        im = qr.make_image(fill_color="black", back_color="white").convert("RGBA")
        im = im.resize((px, px), Image.NEAREST)
    except Exception:
        im = None
    _QR_CACHE[px] = im
    return im


def build_pair_image(code, hover, scale=1.0, frac=1.0):
    """Render the whole pairing card to an RGBA image at `scale`x logical size (for
    HiDPI displays). `frac` (0..1) drives the circular countdown ring. Returns
    (image, button_bbox) in final pixel coordinates."""
    from PIL import Image, ImageDraw, ImageEnhance
    S = _RENDER_SCALE
    W, H = WIN_W, WIN_H
    img = Image.new("RGBA", (W * S, H * S), _hex_rgb(BRAND.BG) + (255,))
    d = ImageDraw.Draw(img)
    cx = W * S / 2

    # Only two sizes: body + display. Hierarchy = weight + color.
    f_title = _font("semibold", FONT_BODY * S)
    f_sub = _font("regular", FONT_BODY * S)
    f_code = _font("bold", FONT_DISPLAY * S)
    f_btn = _font("semibold", FONT_BODY * S)

    def center(text, y, font, fill):
        w = d.textlength(text, font=font)
        d.text((cx - w / 2, y * S), text, font=font, fill=fill)

    # microphone
    mic_sz = int(74 * S)
    mic = make_mic(mic_sz)
    img.paste(mic, (int(cx - mic_sz / 2), int(24 * S)), mic)

    center("Pair a device", 116, f_title, _hex_rgb(BRAND.INK))
    center("Enter this code in the mobile app", 148, f_sub, _hex_rgb(BRAND.MUTED))

    # code card
    cy0, cy1 = 174 * S, 244 * S
    d.rounded_rectangle((30 * S, cy0, (W - 30) * S, cy1), radius=16 * S,
                        fill=_hex_rgb(BRAND.SURFACE) + (255,))
    digits = list(code)
    gap = 9 * S
    widths = [d.textlength(c, font=f_code) for c in digits]
    total = sum(widths) + gap * (len(digits) - 1)
    asc, desc = f_code.getmetrics()
    ty = (cy0 + cy1) / 2 - (asc + desc) / 2
    tx = cx - total / 2
    code_fill = _hex_rgb(BRAND.INK)
    for c, w in zip(digits, widths):
        d.text((tx, ty), c, font=f_code, fill=code_fill)
        tx += w + gap

    # circular countdown ring INSIDE the code card, left of the digits — depletes
    # as the code ages, then the code auto-refreshes. No "expired" text.
    rcx, rcy, rr = 54 * S, (cy0 + cy1) / 2, 11 * S
    rtw = max(1, int(3 * S))
    rbb = (rcx - rr, rcy - rr, rcx + rr, rcy + rr)
    d.ellipse(rbb, outline=_hex_rgb(BRAND.FAINT) + (255,), width=rtw)
    if frac > 0:
        d.arc(rbb, -90, -90 + 360 * frac, fill=_hex_rgb(BRAND.ACCENT_2) + (255,), width=rtw)

    # gradient pill button
    bx0, by0, bx1, by1 = 30 * S, 262 * S, (W - 30) * S, 314 * S
    bw, bh = int(bx1 - bx0), int(by1 - by0)
    grad = Image.new("RGB", (bw, bh))
    gd = ImageDraw.Draw(grad)
    a, b = _hex_rgb(GRADIENT_BRAND[0]), _hex_rgb(GRADIENT_BRAND[1])
    for x in range(bw):
        t = x / max(1, bw - 1)
        gd.line([(x, 0), (x, bh)], fill=tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3)))
    if hover:
        grad = ImageEnhance.Brightness(grad).enhance(1.12)
    bmask = Image.new("L", (bw, bh), 0)
    ImageDraw.Draw(bmask).rounded_rectangle((0, 0, bw - 1, bh - 1), radius=bh // 2, fill=255)
    img.paste(grad, (int(bx0), int(by0)), bmask)
    label = "New code"
    lw = d.textlength(label, font=f_btn)
    la, ld = f_btn.getmetrics()
    d.text((cx - lw / 2, (by0 + by1) / 2 - (la + ld) / 2), label, font=f_btn, fill=(255, 255, 255))

    fw, fh = max(1, round(W * scale)), max(1, round(H * scale))
    final = img.resize((fw, fh), Image.LANCZOS)
    bbox = tuple(round(v * scale) for v in (30, 262, W - 30, 314))
    return final, bbox


def _dark_titlebar(root):
    """Ask Windows for a dark title bar (DWM immersive dark mode). No-op elsewhere."""
    if not sys.platform.startswith("win"):
        return
    try:
        import ctypes
        root.update_idletasks()
        hwnd = ctypes.windll.user32.GetParent(root.winfo_id())
        val = ctypes.c_int(1)
        for attr in (20, 19):  # DWMWA_USE_IMMERSIVE_DARK_MODE (20, or 19 pre-1903)
            ctypes.windll.dwmapi.DwmSetWindowAttribute(
                hwnd, attr, ctypes.byref(val), ctypes.sizeof(val))
    except Exception:
        pass


def _win_scale(root):
    """Display scale factor for the window's monitor (1.0 = 100%, 1.5 = 150%)."""
    if not sys.platform.startswith("win"):
        return 1.0
    try:
        import ctypes
        root.update_idletasks()
        hwnd = ctypes.windll.user32.GetParent(root.winfo_id())
        try:
            dpi = ctypes.windll.user32.GetDpiForWindow(hwnd)
        except Exception:
            dpi = ctypes.windll.user32.GetDpiForSystem()
        return (dpi or 96) / 96.0
    except Exception:
        return 1.0


_pair_win_open = False
_pair_win_lock = threading.Lock()


def open_pair_window(start_tab="code"):
    """Panel with two tabs: 'Device code' (the live 6-digit pairing code) and
    'System info' (server status + a QR to install the phone app). One window at a
    time; runs its own Tk loop on a dedicated thread. start_tab picks the open tab."""
    global _pair_win_open
    with _pair_win_lock:
        if _pair_win_open:
            return
        _pair_win_open = True

    def _run():
        global _pair_win_open
        try:
            import tkinter as tk
            from PIL import ImageTk
        except Exception:
            show_pair_code(PAIRING.open(PAIR_TTL_TRAY), PAIR_TTL_TRAY)
            with _pair_win_lock:
                _pair_win_open = False
            return

        set_dpi_awareness()  # crisp on scaled displays (no bitmap stretch)

        root = tk.Tk()
        root.title(APP_NAME)
        root.configure(bg=BRAND.BG)
        root.resizable(False, False)
        try:
            root.attributes("-topmost", True)
            root.attributes("-toolwindow", True)  # no stray taskbar button
        except Exception:
            pass
        root.update_idletasks()
        scale = _win_scale(root)
        TAB_H = 46
        wd, hd = round(WIN_W * scale), round((WIN_H + TAB_H) * scale)
        x = root.winfo_screenwidth() - wd - round(24 * scale)
        y = root.winfo_screenheight() - hd - round(72 * scale)
        root.geometry(f"{wd}x{hd}+{max(0, x)}+{max(0, y)}")
        _dark_titlebar(root)
        try:
            ic = ImageTk.PhotoImage(make_mic(round(64 * scale)))
            root.iconphoto(True, ic)
            root._ic = ic
        except Exception:
            pass

        # Negative size = pixels in Tk (positive = points, which render ~1.33x bigger
        # at 96dpi and worse on hi-dpi). The code card is drawn in pixels, so use pixels
        # here too or the native text dwarfs it.
        f_tab = (FONT, -round(FONT_BODY * scale))        # tab labels: same as body
        f_body = (FONT, -round(FONT_BODY * scale))       # info-tab content: same as the card

        # ---- tab bar ----
        bar = tk.Frame(root, bg=BRAND.SURFACE, height=round(TAB_H * scale))
        bar.pack(fill="x")
        bar.pack_propagate(False)
        tabs = {}

        def select(name):
            for n, lbl in tabs.items():
                lbl.configure(fg=(BRAND.INK if n == name else BRAND.MUTED))
            code_frame.pack_forget()
            info_frame.pack_forget()
            (code_frame if name == "code" else info_frame).pack()

        for name, text in (("code", "Device code"), ("info", "System info")):
            lbl = tk.Label(bar, text=text, bg=BRAND.SURFACE, fg=BRAND.MUTED,
                           font=f_tab, cursor="hand2")
            lbl.pack(side="left", expand=True, fill="both")
            lbl.bind("<Button-1>", lambda e, n=name: select(n))
            tabs[name] = lbl

        # ---- tab 1: the rendered code card ----
        code_frame = tk.Frame(root, bg=BRAND.BG)
        panel = tk.Label(code_frame, bd=0, bg=BRAND.BG)
        panel.pack()
        cstate = {"hover": False, "bbox": (0, 0, 0, 0)}

        def render():
            remaining = max(0.0, PAIRING.expiry - time.time())
            frac = max(0.0, min(1.0, remaining / PAIR_TTL_TRAY)) if PAIRING.code else 0.0
            img, bbox = build_pair_image(PAIRING.code or "------", cstate["hover"], scale, frac)
            ph = ImageTk.PhotoImage(img)
            panel.configure(image=ph)
            panel.image = ph
            cstate["bbox"] = bbox

        def in_btn(e):
            x0, y0, x1, y1 = cstate["bbox"]
            return x0 <= e.x <= x1 and y0 <= e.y <= y1

        def on_click(e):
            if in_btn(e):
                PAIRING.open(PAIR_TTL_TRAY)
                render()

        def on_motion(e):
            h = in_btn(e)
            if h != cstate["hover"]:
                cstate["hover"] = h
                panel.configure(cursor="hand2" if h else "")
                render()

        panel.bind("<Button-1>", on_click)
        panel.bind("<Motion>", on_motion)

        # ---- tab 2: system info + QR ----
        info_frame = tk.Frame(root, bg=BRAND.BG, width=wd, height=round(WIN_H * scale))
        info_frame.pack_propagate(False)

        def row(k, v):
            r = tk.Frame(info_frame, bg=BRAND.BG)
            r.pack(fill="x", padx=round(34 * scale), pady=round(5 * scale))
            tk.Label(r, text=k, bg=BRAND.BG, fg=BRAND.MUTED, font=f_body).pack(side="left")
            tk.Label(r, text=v, bg=BRAND.BG, fg=BRAND.INK,
                     font=(FONT_MONO, -round(FONT_BODY * scale))).pack(side="right")

        tk.Frame(info_frame, bg=BRAND.BG, height=round(20 * scale)).pack()
        row("Computer", HOSTNAME)
        row("Address", f"{get_local_ip()}:{SERVER_PORT}")
        row("Version", APP_VERSION)
        row("Start with Windows", "On" if autostart_enabled() else "Off")
        qrim = _get_qr(round(132 * scale))
        if qrim is not None:
            qph = ImageTk.PhotoImage(qrim)
            ql = tk.Label(info_frame, image=qph, bg=BRAND.BG, bd=0)
            ql.image = qph
            ql.pack(pady=(round(18 * scale), round(6 * scale)))
            tk.Label(info_frame, text="Scan to install the phone app", bg=BRAND.BG,
                     fg=BRAND.MUTED, font=f_body).pack()
        else:
            tk.Label(info_frame, text="Get the app at voxmanager.com", bg=BRAND.BG,
                     fg=BRAND.MUTED, font=f_body).pack(pady=round(24 * scale))

        def tick():
            if PAIRING.consumed:  # a phone paired -> the code did its job
                on_close()
                return
            if not PAIRING.code or time.time() >= PAIRING.expiry:
                PAIRING.open(PAIR_TTL_TRAY)
            render()
            root.after(1000, tick)

        def on_close():
            global _pair_win_open
            with _pair_win_lock:
                _pair_win_open = False
            try:
                root.destroy()
            except Exception:
                pass

        root.protocol("WM_DELETE_WINDOW", on_close)
        PAIRING.open(PAIR_TTL_TRAY)
        render()
        select(start_tab if start_tab in ("code", "info") else "code")
        tick()
        root.mainloop()

    threading.Thread(target=_run, daemon=True).start()


# ---------------------------------------------------------------- autostart
# "Start with Windows" toggles a per-user Run key (HKCU). No admin rights, no
# service install — the tray app simply relaunches at logon with the same port it
# currently uses. No-op off Windows.

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = APP_NAME  # the registry value name (also what shows in Task Manager > Startup)


def _autostart_command(port):
    """The exact command Windows runs at logon. Prefers pythonw.exe so the server
    starts headless (tray only, no console flash). When frozen (PyInstaller) it is
    just the exe itself."""
    if getattr(sys, "frozen", False):
        parts = [sys.executable]
    else:
        exe = Path(sys.executable)
        launcher = exe.with_name("pythonw.exe")          # silent interpreter if present
        launcher = str(launcher if launcher.exists() else exe)
        parts = [launcher, str(Path(__file__).resolve())]
    if port != PORT:
        parts += ["--port", str(port)]
    return " ".join(f'"{p}"' if " " in p else p for p in parts)


def autostart_enabled():
    if not sys.platform.startswith("win"):
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, RUN_VALUE)
            return True
    except OSError:                                       # key/value missing
        return False


def autostart_set(enable, port=PORT):
    """Add or remove the logon entry. Idempotent."""
    if not sys.platform.startswith("win"):
        return False
    import winreg
    try:
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            if enable:
                winreg.SetValueEx(k, RUN_VALUE, 0, winreg.REG_SZ, _autostart_command(port))
            else:
                try:
                    winreg.DeleteValue(k, RUN_VALUE)
                except FileNotFoundError:
                    pass
        return True
    except OSError:
        return False


# ---------------------------------------------------------------- auto-update
# Checks a small JSON manifest for a newer build. If one exists, the tray shows
# "Update to x.y.z…"; clicking it downloads the installer, the app exits (so its
# files unlock), and the installer upgrades in place and relaunches. No silent
# background install (intentional: the binary is unsigned for now, so the user
# stays in control). Offline / no manifest -> simply no update, no noise.

def _ver_tuple(s):
    out = []
    for part in str(s).split("."):
        digits = "".join(c for c in part if c.isdigit())
        out.append(int(digits) if digits else 0)
    return tuple(out)


def _is_newer(remote, local):
    return _ver_tuple(remote) > _ver_tuple(local)


def check_for_update():
    """Fetch the manifest and set UPDATE_INFO if a newer version is published."""
    global UPDATE_INFO
    url = os.environ.get("VOXMANAGER_UPDATE_URL", UPDATE_MANIFEST_URL)
    try:
        with urllib.request.urlopen(url, timeout=8) as resp:
            # utf-8-sig, not utf-8: a manifest saved by an editor or by PowerShell
            # can carry a BOM, and json.loads() would raise on it. The exception is
            # swallowed by the caller, so the symptom would be updates silently
            # never appearing, which is the worst way for this to fail.
            data = json.loads(resp.read().decode("utf-8-sig"))
        ver = str(data.get("version", "")).strip()
        dl = str(data.get("url", "")).strip()
        if ver and dl and _is_newer(ver, APP_VERSION):
            UPDATE_INFO = {"version": ver, "url": dl,
                           "sha256": str(data.get("sha256", "")).strip().lower(),
                           "notes": data.get("notes", "")}
            print(f"  [update] available: {ver}")
        else:
            UPDATE_INFO = None
    except Exception:
        pass  # offline / no manifest / bad JSON -> no update, stay quiet


def _update_loop():
    while True:
        check_for_update()
        time.sleep(24 * 3600)


def download_and_install_update(info):
    """Download the installer to a temp file, verify its SHA-256 (if the manifest
    provides one), and launch it. Caller exits the app afterwards so the installer
    can replace the running files."""
    import tempfile
    url = info["url"]
    name = url.rsplit("/", 1)[-1] or "VoxManager-Setup.exe"
    dest = os.path.join(tempfile.gettempdir(), name)
    urllib.request.urlretrieve(url, dest)
    want = info.get("sha256", "")
    if want:
        with open(dest, "rb") as f:
            got = hashlib.sha256(f.read()).hexdigest()
        if got != want:
            raise RuntimeError(f"checksum mismatch (expected {want[:12]}…, got {got[:12]}…)")
    if sys.platform.startswith("win"):
        os.startfile(dest)  # the per-user installer needs no elevation
    else:
        subprocess.Popen([dest])
    return dest


def run_tray(server, port):
    import pystray
    global SERVER_PORT
    SERVER_PORT = port
    image = make_tray_image()

    # Check for updates now and once a day after.
    threading.Thread(target=_update_loop, daemon=True).start()

    def on_pair(icon, item):
        open_pair_window("code")

    def on_info(icon, item):
        open_pair_window("info")

    def on_toggle_autostart(icon, item):
        autostart_set(not autostart_enabled(), port=port)

    def on_update(icon, item):
        info = UPDATE_INFO
        if not info:
            return
        try:
            download_and_install_update(info)
        except Exception as e:
            print(f"  [update] failed: {e}")
            return
        server.shutdown()   # release our files so the installer can replace them
        icon.stop()

    def on_quit(icon, item):
        server.shutdown()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem(f"{APP_NAME}  v{APP_VERSION}  ·  port {port}", None, enabled=False),
        pystray.MenuItem("Pair a device…", on_pair, default=True),
        pystray.MenuItem("System info", on_info),
        pystray.MenuItem("Start with Windows", on_toggle_autostart,
                         checked=lambda item: autostart_enabled()),
        pystray.MenuItem(lambda item: f"Update to {UPDATE_INFO['version']}…" if UPDATE_INFO else "",
                         on_update, visible=lambda item: UPDATE_INFO is not None),
        pystray.MenuItem("Quit", on_quit),
    )
    pystray.Icon("voxmanager", image, APP_NAME, menu).run()


# ---------------------------------------------------------------- main

def main():
    # First thing, so a crash during startup is captured too. The handle is kept in
    # a module-level name because faulthandler writes to it from a signal handler.
    global _crash_log_handle
    _crash_log_handle = _enable_crash_log()

    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    set_dpi_awareness()
    set_app_user_model_id()

    parser = argparse.ArgumentParser()
    parser.add_argument("--pair", action="store_true", help="print a pairing code and exit")
    parser.add_argument("--reset", action="store_true", help="new secret (re-pair all phones)")
    parser.add_argument("--port", type=int, default=PORT, help="HTTP port (default 8765)")
    parser.add_argument("--console", action="store_true", help="console mode (debug, no tray)")
    parser.add_argument("--autostart", choices=["on", "off", "status"],
                        help="manage 'Start with Windows' (logon entry) and exit")
    parser.add_argument("--version", action="store_true", help="print version and exit")
    args = parser.parse_args()

    if args.version:
        print(APP_VERSION)
        return

    if args.autostart:
        if args.autostart == "status":
            print("  autostart:", "ON" if autostart_enabled() else "off")
        else:
            ok = autostart_set(args.autostart == "on", port=args.port)
            print(f"  autostart -> {args.autostart}" + ("" if ok else "  (failed / not Windows)"))
        return

    secret = load_or_create_secret(reset=args.reset)

    if args.pair:
        code = PAIRING.open(PAIR_TTL_CONSOLE)
        print(f"\n  Pairing code: {code}   (valid {PAIR_TTL_CONSOLE // 60} min)")
        print("  Enter it in the app: Settings -> Pair new PC\n")
        print("  NOTE: this only works while THIS server is running, because the")
        print("  phone fetches the secret from it. Use --console to keep it up.\n")
        return

    if args.console:
        try:
            server = ExclusiveHTTPServer(("0.0.0.0", args.port), make_handler(secret))
        except OSError:
            server = ExclusiveHTTPServer(("0.0.0.0", 0), make_handler(secret))
        actual_port = server.server_address[1]
        if actual_port != args.port:
            print(f"  (port {args.port} was busy -> using free port {actual_port})")
        threading.Thread(target=run_discovery_server, args=(actual_port,), daemon=True).start()
        code = PAIRING.open(PAIR_TTL_CONSOLE)
        print("=" * 52)
        print(f"  Vox Manager server  -  {HOSTNAME}")
        print(f"  http://{get_local_ip()}:{actual_port}")
        print(f"  Pairing code: {code}   (valid {PAIR_TTL_CONSOLE // 60} min)")
        print("  In the app: Settings -> Pair new PC -> enter the code")
        print("  Ctrl+C to stop")
        print("=" * 52 + "\n")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            server.shutdown()
        return

    # Production: headless tray (no console window).
    server, port = start_background(secret, args.port)
    try:
        run_tray(server, port)
    except ImportError:
        while True:
            time.sleep(3600)


if __name__ == "__main__":
    main()
