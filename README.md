# Vox Manager, Voice to PC

**Turn your phone into a close-talk microphone that types on your PC.**

Hold your phone near your mouth, speak (even quietly), and the recognised text is
typed into the active window on your computer over Wi-Fi. No cloud account, no
cables, pairing is a one-time 6-digit code, and every message is mutually
authenticated.

| | |
|---|---|
| **Brand** | Vox Manager |
| **Category** | Voice to PC dictation |
| **Platforms** | Android app · Windows PC server (macOS / Linux: cross-platform code, packaging in progress) |
| **Status** | Working end-to-end (dictation, pairing, security). Pre-release. |

---

## How it works

```
 Android app  ──speech──►  recognised text
      │
      │  signed HTTP POST over Wi-Fi (HMAC-SHA256, mutual)
      ▼
 PC server  ──►  types into the active window (pynput)
```

- The phone finds the PC by UDP broadcast (no IP typing).
- Pairing: the PC shows a **6-digit code**; the phone enters it once and receives a
  256-bit shared secret. After that, **every request is signed**, and the **server
  signs its replies** too, so the phone never types into a stranger's machine.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/SECURITY.md`](docs/SECURITY.md) for details.

## Repository layout

```
android/   Android app (Kotlin), the microphone client
server/    PC server (Python), cross-platform, types on the computer
brand/     Brand tokens + generator, single source of truth for colors/icon
tests/     Automated tests, server security core
docs/      Architecture, security, roadmap
```

## Build & run

- **Server:** see [`server/README.md`](server/README.md) (Windows / macOS / Linux).
- **Android:** `cd android && ./gradlew assembleDebug` (JDK 17, Android SDK).
- **Tests:** `python -m unittest discover -s tests` (no extra dependencies).

## Roadmap

Next: release signing for Google Play, macOS/Linux server packaging, and
**on-PC speech recognition** (Whisper) so accuracy stops depending on each phone's
recognizer. See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Contributing

Build instructions, what needs discussing before you write it, and how to report a
bug: [`CONTRIBUTING.md`](CONTRIBUTING.md).

Found a security problem? Report it privately, not in an issue:
[`SECURITY.md`](SECURITY.md).

## License

[GNU GPL v3.0](LICENSE). You may use, study, modify, and redistribute it; derived
works must stay under the GPL. The project is free and open source; future paid
add-ons (e.g. a cloud AI layer) are offered as a separate service on top.
