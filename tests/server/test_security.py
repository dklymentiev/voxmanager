# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Dmytro Klymentiev
"""Unit tests for the Vox Manager PC-server security core.

Covers the parts that MUST stay correct for the product to be safe:
  - HMAC request signing (and parity with the Kotlin client's format)
  - nonce replay protection
  - 6-digit pairing code: format, one-time use, wrong-code handling, expiry, lockout

Run:  python -m unittest discover -s tests   (from the repo root, no extra deps)
"""

import hashlib
import hmac
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "server"))
import voxmanager_server as v


class TestSign(unittest.TestCase):
    def test_matches_reference_hmac(self):
        secret = "ab" * 32  # 64 hex chars = 256-bit key
        # Exactly the layout the client signs: method\npath\nts\nnonce\nbody
        msg = "\n".join(["POST", "/", "1700000000000", "deadbeef", '{"text":"hi"}'])
        expected = hmac.new(bytes.fromhex(secret), msg.encode("utf-8"),
                            hashlib.sha256).hexdigest()
        self.assertEqual(v.sign(secret, msg), expected)

    def test_output_is_lowercase_hex_64(self):
        sig = v.sign("00" * 32, "anything")
        self.assertEqual(len(sig), 64)
        self.assertEqual(sig, sig.lower())

    def test_changes_with_key_and_message(self):
        self.assertNotEqual(v.sign("00" * 32, "a"), v.sign("11" * 32, "a"))
        self.assertNotEqual(v.sign("00" * 32, "a"), v.sign("00" * 32, "b"))


class TestNonceReplay(unittest.TestCase):
    def test_fresh_then_replay_rejected(self):
        now = int(time.time() * 1000)
        nonce = f"nonce-{now}-A"
        self.assertTrue(v.nonce_is_fresh(nonce, now))   # first sighting
        self.assertFalse(v.nonce_is_fresh(nonce, now))  # replay -> rejected

    def test_distinct_nonces_both_fresh(self):
        now = int(time.time() * 1000)
        self.assertTrue(v.nonce_is_fresh(f"x-{now}", now))
        self.assertTrue(v.nonce_is_fresh(f"y-{now}", now))


class TestPairing(unittest.TestCase):
    def _wrong_of(self, code):
        return "000000" if code != "000000" else "111111"

    def test_code_is_six_digits(self):
        code = v.Pairing().open(120)
        self.assertEqual(len(code), 6)
        self.assertTrue(code.isdigit())

    def test_correct_code_is_one_time_use(self):
        p = v.Pairing()
        code = p.open(120)
        self.assertIs(p.try_code(code), True)   # accepted once
        self.assertIsNone(p.try_code(code))     # consumed -> no active pairing

    def test_wrong_code_does_not_burn_a_valid_one(self):
        p = v.Pairing()
        code = p.open(120)
        self.assertIs(p.try_code(self._wrong_of(code)), False)
        self.assertIs(p.try_code(code), True)   # right code still works

    def test_lockout_after_max_attempts(self):
        p = v.Pairing()
        code = p.open(120)
        wrong = self._wrong_of(code)
        for _ in range(v.PAIR_MAX_ATTEMPTS):
            p.try_code(wrong)
        # too many wrong tries -> even the correct code is now refused
        self.assertIsNone(p.try_code(code))

    def test_expired_code_refused(self):
        p = v.Pairing()
        code = p.open(120)
        p.expiry = time.time() - 1  # force expiry
        self.assertIsNone(p.try_code(code))

    def test_no_active_pairing_returns_none(self):
        p = v.Pairing()  # never opened
        self.assertIsNone(p.try_code("123456"))


class TestPairingKeyExchange(unittest.TestCase):
    """v3 pairing: the secret is sealed with X25519 + HKDF-SHA256 + AES-256-GCM.
    These lock the exact wire format the Kotlin client (BouncyCastle) must match."""

    def _open(self, sealed, phone_priv, phone_pub, code):
        from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PublicKey
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.hazmat.primitives.kdf.hkdf import HKDF
        from cryptography.hazmat.primitives import hashes
        server_pub = X25519PublicKey.from_public_bytes(bytes.fromhex(sealed["server_pub"]))
        shared = phone_priv.exchange(server_pub)
        key = HKDF(algorithm=hashes.SHA256(), length=32, salt=v.PAIR_HKDF_SALT,
                   info=b"secret-wrap|" + code.encode("utf-8")).derive(shared)
        aad = phone_pub + bytes.fromhex(sealed["server_pub"])
        return AESGCM(key).decrypt(bytes.fromhex(sealed["nonce"]),
                                   bytes.fromhex(sealed["ct"]), aad)

    def _phone_keypair(self):
        from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
        from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
        priv = X25519PrivateKey.generate()
        pub = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        return priv, pub

    def test_sealed_secret_round_trips(self):
        secret, code = "ab" * 32, "123456"
        priv, pub = self._phone_keypair()
        sealed = v.seal_secret_for_phone(secret, code, pub.hex())
        self.assertEqual(sealed["v"], 3)
        self.assertEqual(self._open(sealed, priv, pub, code).decode(), secret)

    def test_wrong_code_cannot_open(self):
        # The code is bound into the HKDF info, so a wrong code yields a wrong key.
        from cryptography.exceptions import InvalidTag
        priv, pub = self._phone_keypair()
        sealed = v.seal_secret_for_phone("cd" * 32, "111111", pub.hex())
        with self.assertRaises(InvalidTag):
            self._open(sealed, priv, pub, "222222")

    def test_secret_not_present_in_cleartext(self):
        secret = "ef" * 32
        priv, pub = self._phone_keypair()
        sealed = v.seal_secret_for_phone(secret, "000000", pub.hex())
        self.assertNotIn(secret, json_dumps(sealed))


class TestActiveWindow(unittest.TestCase):
    def test_returns_string(self):
        # Surfaced in the signed status so the phone can show the typing target.
        # Windows returns a real title; other platforms return "". Never raises.
        self.assertIsInstance(v.active_window_title(), str)


class _FakeClip:
    """A clipboard whose write only takes effect after `lands_after` copies, mimicking
    a clipboard manager / app briefly locking it. paste() returns the stale value until
    then -- exactly the condition that made Ctrl+V insert the previous contents."""
    def __init__(self, initial, lands_after=0):
        self.value = initial
        self.lands_after = lands_after
        self.copies = 0

    def copy(self, text):
        self.copies += 1
        if self.copies > self.lands_after:
            self.value = text

    def paste(self):
        return self.value


class TestClipboardConfirm(unittest.TestCase):
    """_clipboard_put must only report success once the clipboard is CONFIRMED to hold
    our text; otherwise type_text would paste whatever was there before."""

    def _install(self, fake):
        sys.modules["pyperclip"] = fake               # _clipboard_put imports it lazily
        self.addCleanup(lambda: sys.modules.pop("pyperclip", None))

    def test_confirms_when_write_lands_immediately(self):
        fake = _FakeClip("OLD-CLIPBOARD", lands_after=0)
        self._install(fake)
        self.assertTrue(v._clipboard_put("voice text"))
        self.assertEqual(fake.paste(), "voice text")

    def test_confirms_after_a_few_locked_attempts(self):
        fake = _FakeClip("OLD-CLIPBOARD", lands_after=3)  # first writes are dropped
        self._install(fake)
        self.assertTrue(v._clipboard_put("voice text", timeout=0.6))
        self.assertEqual(fake.paste(), "voice text")

    def test_returns_false_if_write_never_lands(self):
        # Clipboard stuck on old content -> must report failure so the caller does NOT
        # paste the stale value (it falls back to char typing instead).
        fake = _FakeClip("OLD-CLIPBOARD", lands_after=10 ** 9)
        self._install(fake)
        self.assertFalse(v._clipboard_put("voice text", timeout=0.1))
        self.assertEqual(fake.paste(), "OLD-CLIPBOARD")


class TestUnicodeInput(unittest.TestCase):
    """SendInput Unicode injection (Windows): the layout-independent typing path.
    Builds key events without touching the clipboard or the keyboard layout."""

    def test_ascii_char_emits_unicode_down_then_up(self):
        ev = v._build_unicode_inputs("a")
        self.assertEqual(len(ev), 2)
        self.assertEqual(ev[0].u.ki.wScan, ord("a"))
        self.assertTrue(ev[0].u.ki.dwFlags & v.KEYEVENTF_UNICODE)
        self.assertEqual(ev[0].u.ki.dwFlags & v.KEYEVENTF_KEYUP, 0)   # down
        self.assertTrue(ev[1].u.ki.dwFlags & v.KEYEVENTF_KEYUP)       # up

    def test_cyrillic_sent_as_real_codepoint(self):
        # The whole point: 'я' goes as U+044F, not transliterated via the layout.
        ev = v._build_unicode_inputs("я")
        self.assertEqual(ev[0].u.ki.wScan, ord("я"))
        self.assertTrue(ev[0].u.ki.dwFlags & v.KEYEVENTF_UNICODE)

    def test_newline_becomes_enter_key_not_unicode(self):
        ev = v._build_unicode_inputs("\n")
        self.assertEqual(len(ev), 2)
        self.assertEqual(ev[0].u.ki.wVk, v.VK_RETURN)
        self.assertEqual(ev[0].u.ki.dwFlags & v.KEYEVENTF_UNICODE, 0)

    def test_crlf_collapses_to_one_enter(self):
        self.assertEqual(len(v._build_unicode_inputs("\r\n")), 2)  # not two Enters

    def test_non_bmp_emoji_is_surrogate_pair(self):
        ev = v._build_unicode_inputs("\U0001F600")   # grinning face
        self.assertEqual(len(ev), 4)                  # 2 UTF-16 units, down+up each
        self.assertEqual(ev[0].u.ki.wScan, 0xD83D)
        self.assertEqual(ev[2].u.ki.wScan, 0xDE00)


def json_dumps(obj):
    import json
    return json.dumps(obj)


if __name__ == "__main__":
    unittest.main(verbosity=2)
