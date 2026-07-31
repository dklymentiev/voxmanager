# Roadmap

Where the project is and what comes next. Security properties that are already
shipped are described in [`SECURITY.md`](SECURITY.md), not here.

## Shipped

- End-to-end dictation (phone mic -> typed on the PC), live-verified on Windows.
- **Encrypted pairing (v3).** The 256-bit secret is sealed with X25519 ECDH +
  HKDF-SHA256 + AES-256-GCM, with the 6-digit code mixed into the key derivation.
  It is never transmitted in the clear.
- **Encrypted, signed transport.** Every payload is AES-256-GCM (per-link key from
  HKDF over the shared secret) inside an HMAC-SHA256 envelope, signed in both
  directions, with nonce replay rejection and a 2-minute timestamp window kept tight
  by the unsigned `/time` beacon.
- **Bounded, threaded HTTP front door.** One thread per connection and a cap on the
  request body, so an unauthenticated peer on the same Wi-Fi cannot wedge the server
  by announcing a body it never sends.
- Pairing-code hardening: single use, expiry, lockout after 5 wrong tries,
  rate-limited `/request-pair`, `chmod 600` on the secret (POSIX).
- Typing-target awareness: the signed status reports the focused window, and the
  phone shows where the text will land when dictation starts.
- Live connection state in Settings (a real probe, not persisted state).
- Full design pass (dark theme, Aurora-Mic brand) on a single-source brand pipeline.
- Security core under unit test; `packaging/release.ps1` will not build on a red test.

## Next

1. **On-PC speech recognition.** Accuracy currently depends on each phone's
   recognizer, which varies widely across vendors and Android versions. This is both
   the biggest reliability hole and the same component as the product's wedge
   (phone-as-microphone). Plan: the phone streams raw audio, the PC runs
   **faster-whisper** (int8) with VAD -- `tiny`/`base` run faster than real time on
   weak CPUs, `small` with a GPU on strong ones; **Vosk** as an ultra-light fallback.
2. **Release signing** for the Android app, and a Play listing.
3. **macOS / Linux server.** The code is already cross-platform; the packaging
   scripts exist but are unverified on real hardware. Linux input is the known gap:
   `pynput` is X11-only, so keystroke injection does not work under Wayland -- that
   needs a second input backend.
4. **Code signing** for the Windows executable (SmartScreen warns until then).

## Known gaps

- **Active MITM at pairing time.** The 6-digit code still travels in the pairing
  request, so an attacker who can intercept and modify traffic *during the pairing
  window* is out of scope. Closing it needs a true PAKE (SPAKE2), which derives the
  secret on both sides instead of sending it. A passive sniffer is already covered.
- **Transport is HTTP at the socket level.** The bodies are encrypted, so nothing
  readable crosses the network, but there is no TLS: a self-signed certificate has
  nothing to pin against at pairing time, which is why the payload layer carries the
  encryption instead.
- **The phone's secret sits in plain `SharedPreferences`.** Backup is disabled, but
  a rooted or compromised phone can read it. It belongs in the Android Keystore.
- **No per-app allow-list for typing.** By design the server types into whatever
  window has focus; the phone's start/stop control is the arm/disarm.
- **No Android-side tests.** The wire format is mirrored by hand between the Python
  server and the Kotlin client, and only the Python half is covered.
