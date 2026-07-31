# Changelog

All notable changes to Vox Manager are recorded here.
Format follows [Keep a Changelog](https://keepachangelog.com/); versions follow
[Semantic Versioning](https://semver.org/). App `versionName` mirrors these.

## [Unreleased]

## [1.3.0] - 2026-07-30

### Added
- **Crash log.** The tray build is `--noconsole`, so a traceback, or a hard abort
  inside a C extension, used to leave nothing behind but a line in the Windows event
  log naming a DLL and an offset. `faulthandler` plus both excepthooks now write to
  `~/.voxmanager/crash.log`. The typing log moved there too: it had been written next
  to `__file__`, which under `--onefile` is the extracted temp directory that
  PyInstaller wipes on exit, so it vanished exactly when it was needed.

### Security
- **An unauthenticated peer can no longer wedge the PC server.** The HTTP server ran
  single-threaded and read the request body straight off the announced
  `Content-Length`: necessarily before the signature over that body could be
  checked. Anyone on the same Wi-Fi could open a connection, claim a large body and
  send nothing, and dictation stopped for everyone until the socket died; a
  non-numeric `Content-Length` raised instead of answering. The server now handles
  each connection on its own thread with a 15-second idle timeout, caps a request
  body at 1 MiB (answering `413` from the header, without reading), and rejects a
  malformed length with `400`. Typing is serialised behind a lock so concurrent
  requests cannot interleave their characters. Covered by
  `tests/server/test_http_limits.py`, including the stalled-peer regression.

### Changed
- **Product identity settled on Vox Manager, shipping under `voxmanager.com`.**
  Names and identifiers were reworked across the whole tree; nothing had ever been
  published, so no Play listing, install base or review history was affected.
  The parts that are not cosmetic:
  - Android `applicationId` and `namespace` are `com.voxmanager`. The app installs
    as a new app with empty storage, so any device paired against an earlier build
    has to be paired again.
  - The PC server ships as `VoxManager-Server.exe`; the installer is
    `VoxManager-Setup-<ver>.exe`, installs to `%LOCALAPPDATA%\Programs\Vox Manager`
    and carries a **new Inno Setup `AppId`**, so it is a separate product rather
    than an in-place upgrade of an earlier install. Uninstall any earlier build
    first: both servers bind port 8765 and would collide.
  - The HKDF salts are `voxmanager-transport-v1` and `voxmanager-pair-v3`, so this
    wire protocol is deliberately incompatible with pre-rename builds on either
    side. Re-pairing is required regardless, see above.
  - The update manifest is read from `voxmanager.com/latest.json`, overridable via
    `VOXMANAGER_UPDATE_URL`.
  - The last identifiers carrying an older working name were renamed too, so nothing
    in the tree contradicts the product: the config dir holding the machine secret is
    `~/.voxmanager`, the UDP discovery handshake is `VOXMANAGER_DISCOVER` /
    `VOXMANAGER_SERVER`, and the Android theme and notification channel follow. These
    are wire-level and storage-level: a server and an app that disagree on them will
    not even discover each other, and the server generates a fresh machine secret on
    first run under the new directory. Both halves must be rebuilt together and every
    phone paired again. Doing it now costs one re-pair, because nothing is published;
    doing it after launch would cut every user off.

### Fixed
- **Reconnect restores a known PC before scanning the network.** A phone that roams
  between networks (home ↔ work), each with its own paired PC, would get stuck on a
  spinner: connect ran UDP discovery first and then tried saved IPs serially, so the
  away PC's IP blocked ~5s on a timeout before the reachable one was reached, and a
  flaky discovery left the right PC sitting in the "new" list. Now connect probes
  every known PC's last IP **directly and in parallel** (short timeout, authenticated
  by the paired secret) and binds the first that answers; the full UDP scan only runs
  if no known server responds. The winning PC's IP is refreshed so the next restore is
  a direct hit. Switching between home and work now reconnects in ~1s with no scan.

## [1.2.2] - 2026-06-23

### Fixed
- **Linux server build now actually runs.** The PyInstaller recipe
  (`packaging/build_linux.sh`) was missing two hidden imports, both caught while
  verifying the build end-to-end on Ubuntu 24.04 (xrdp X11 session):
  `pynput.mouse._xorg` (the frozen binary crashed on startup) and
  `PIL._tkinter_finder` (the pairing window rendered all-black with no QR/tabs).
  With both added, a phone pairs and dictation types into the active window.
  Documented the runtime needs: `python3-tk`, an AppIndicator backend, and an
  **X11 display**: `pynput` is X11-only, so under a Wayland desktop it can't
  inject keystrokes into native windows (use an "Ubuntu on Xorg" / xrdp session).

## [1.2.1] - 2026-06-22

### Fixed
- **Typing no longer touches the clipboard (Windows).** Dictated text is now injected
  with `SendInput` + `KEYEVENTF_UNICODE`, which delivers the literal code points to the
  focused app, independent of the keyboard layout. The clipboard is no longer read,
  overwritten, or cleared, so a previously copied document can no longer be pasted in
  place of the voice text. Falls back to per-char typing only if `SendInput` is
  unavailable; macOS/Linux still use a read-back-confirmed clipboard paste.

### Security
- **Encrypted transport.** Post-pairing payloads are now encrypted with AES-256-GCM
  (key derived from the shared secret via HKDF-SHA256), not just HMAC-signed. The
  dictated text no longer travels in cleartext, so a passive Wi-Fi sniffer can't read
  it. Signing/replay protection is unchanged (the signature now covers the
  ciphertext). Server + app must both be on this version (wire-protocol change); no
  re-pairing needed. Bumps version to 1.2.0.

### Added
- **Server auto-update.** The tray server checks a small JSON manifest
  (`voxmanager.com/latest.json`) on startup and daily; when a newer build is
  published it shows "Update to x.y.z…" in the tray. Clicking downloads the
  installer, verifies its SHA-256, exits the app (so files unlock), and the
  installer upgrades in place and relaunches. No silent background install while
  the binary is unsigned. `release.ps1` now emits `latest.json` (version + sha256)
  next to the installer and checks the server's `APP_VERSION` matches the tag;
  `--version` prints the version.
- **Onboarding now explains the PC server.** A fresh Play install previously went
  straight to pairing with nothing to find. New first-run step "Set up your PC"
  tells the user Vox Manager needs a small desktop app on their computer and links to
  `voxmanager.com` to download it. The pairing screen's empty state ("no computers
  found") and a new Settings row also point to `voxmanager.com`. (Link target goes
  live only once the site is up.)

### Fixed
- **Android 15 edge-to-edge clipping.** After targeting API 35 the app drew under the
  status/navigation bars, clipping the bottom waveform control. Opted out of forced
  edge-to-edge so the system bars keep their own space again.
- **Dictation now types in the spoken language regardless of keyboard layout.**
  Text was inserted with per-key synthetic input, which Windows interprets through
  the active layout, so dictating Cyrillic while an English layout was active came
  out garbled/Latin. Insertion now goes through the clipboard (atomic Ctrl/Cmd+V),
  which carries real Unicode and is layout-independent; this also fixes the old
  dropped/reordered-first-character bug in apps like Word. The user's clipboard is
  saved and restored. Adds `pyperclip` (+ `pywin32` on Windows for a reliable paste).

### Added
- **Start with Windows.** New tray checkbox ("Start with Windows") toggles a
  per-user logon entry (HKCU `...\Run`) so the server launches headless at sign-in.
  No admin rights or service install; relaunch preserves the current `--port` and
  uses `pythonw.exe` so there is no console flash. Also scriptable:
  `--autostart on|off|status`.

## [1.1.0] - 2026-06-19

Security hardening pass: the pairing secret is no longer sent in the clear, the
replay window is tight, and the app shows where dictated text will land. Verified
end-to-end on a real device.

### Security
- **Encrypted pairing (v3).** The phone sends an ephemeral X25519 public key with
  the 6-digit code; the server seals the machine secret with X25519 ECDH +
  HKDF-SHA256 + AES-256-GCM and returns it. The secret is **never transmitted in the
  clear**, so a passive Wi-Fi sniffer at pairing time can no longer capture it.
  (An active on-path MITM is still out of scope; that would need a PAKE / SPAKE2.)
- **Tight replay window.** Shrunk 24h to 2 min; the phone learns the server-clock
  offset via an unsigned `GET /time` beacon and stamps signed requests in server
  time, so clock drift no longer needs a wide window. A captured request is now
  replayable for at most ~2 min, even across a server restart.
- **`allowBackup=false`** so the 256-bit secret never lands in cloud backup
  (Android Auto Backup had been silently restoring it on reinstall).
- Removed the wildcard CORS header; rate-limited the unauthenticated
  `/request-pair`; `chmod 600` on `~/.voxmanager/config.json` (POSIX).

### Added
- **Typing-target awareness.** The signed status reports the PC's focused-window
  title; the app shows "→ <window>" when dictation starts, so you can see where
  text will go (refreshed at start). Documented in `docs/SECURITY.md`.
- Pairing **auto-submits** on the 6th digit; the PC pairing-code window closes
  itself once a phone pairs.

### Fixed
- Settings no longer shows a stale **"Connected"**: status is driven by a live
  probe (`verifyConnection`), not a persisted guess.
- The main screen no longer prints "Connecting" twice, the top-left is a single
  pulsing dot.

### Changed
- Server now depends on `cryptography`; Android adds BouncyCastle for the pairing
  key exchange.

## [1.0.0] - 2026-06-19

First tracked release. Dictation works end-to-end (phone mic → typed on the PC).

### Added
- **Android app** (`com.voxmanager`): single-screen dictation UI, the waveform is
  both indicator and start/stop control; live streamed text.
- **PC server** (`server/voxmanager_server.py`): receives signed text and types it
  into the active window; system-tray icon; rendered pairing window with a 6-digit
  code, circular countdown ring, and auto-refresh.
- **Security v2**: mutual HMAC-SHA256 auth; 6-digit pairing (single-use, expiry,
  lockout); the server signs its replies so the phone never types into a rogue PC.
- **Auto-connect + auto-start**: on a fresh foreground connection the app connects
  and starts dictation without a second tap.
- **Tap-to-reconnect**: tapping the wave while disconnected forces a fresh connect.
- **Keep screen awake** during dictation; clean shutdown when the task is removed.
- **Brand pipeline**: `brand/tokens.json` is the single source of truth; generates
  Android colors, server tokens, and the brandbook. One "Aurora Mic" icon master.
- **Cross-platform server**: Windows verified; macOS/Linux packaging scripts added
  (pending verification on real hardware).
- **Tests**: server security core (HMAC signing, nonce replay, pairing-code logic).
- **Docs**: architecture, security model, roadmap; investor-ready repo layout
  (`android/ server/ brand/ tests/ docs/ archive/`).

### Notes
- Phone-side recognition is hardened (prefers an online Google recognizer with
  fallbacks) but still device-dependent; on-PC recognition (Whisper) is planned, 
  see `docs/ROADMAP.md`.
