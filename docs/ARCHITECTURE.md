# Architecture

Two components talk over the local Wi-Fi network: the **Android app** (the
microphone) and the **PC server** (the typist).

```
┌─────────────────────────┐         Wi-Fi (LAN)         ┌──────────────────────────┐
│  Android app            │                             │  PC server (Python)      │
│                         │   1. UDP discover :8767     │                          │
│  SpeechRecognizer  ─────┼───────────────────────────► │  discovery responder     │
│         │               │   ◄─── ip:port:hostname ─── │                          │
│         ▼               │                             │                          │
│  recognised phrase      │   2. signed HTTP POST /     │  verify HMAC + nonce     │
│  WifiKeyboardManager ───┼───────────────────────────► │  pynput types it into    │
│   (HMAC sign + verify)  │   ◄── signed reply ──────── │  the active window       │
└─────────────────────────┘                             └──────────────────────────┘
```

## Android app (`android/`)

- `MainActivity`: orchestration: onboarding gate, auto-connect, reconnect states.
- `ui/KeyboardFragment`: the one screen: the waveform is both indicator and
  start/stop button; streams recognised text; keeps the screen awake while dictating.
- `ui/PairActivity`: scan → list discovered PCs → enter the 6-digit code.
- `ui/SettingsActivity`: paired-PC list, add/remove, language.
- `service/VoiceService`: foreground service hosting `SpeechManager`.
- `speech/SpeechManager`: wraps Android `SpeechRecognizer`; prefers an online
  Google recognizer and favours online recognition for cross-device uniformity.
- `wifi/WifiKeyboardManager`: discovery + HMAC signing/verification + pairing.
- `data/ServerStore`: persists `PairedPc(secret, hostname, ip)`.

## PC server (`server/`)

Single file `voxmanager_server.py`:
- **HTTP handler**: verifies each request's signature, then types via `pynput`.
- **Discovery responder**: answers UDP broadcasts with `ip:port:hostname`.
- **Tray + pairing window**: `pystray` icon; a rendered (Pillow) pairing window
  shows the 6-digit code with a circular countdown that auto-refreshes.

## Networking

- **Discovery:** UDP `:8767` (separate from the v1 prototype's `:8766`).
- **HTTP:** `:8765`, auto-falls back to a free port if taken; the actual port is
  advertised via discovery, so the phone always finds it.

## Brand pipeline (`brand/`)

`brand/tokens.json` is the single source of truth. `brand/generate.py` emits the
Android `colors.xml`, the server's `brand_tokens.py`, and `brandbook.html`. The mic
icon has one master (`brand/icon/aurora-mic.svg`) mirrored by the tray, the pairing
window, the launcher and `app.ico`.
