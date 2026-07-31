# Play screenshots

Put 2-8 phone screenshots here (PNG/JPG, min side ≥320px, 9:16 portrait works well).

## Suggested shots (the product story)
1. Idle/connected, "Tap the wave to start talking".
2. Dictating, live text on screen, waveform active.
3. Pairing, the 6-digit code entry.
4. Settings, paired PC list.
5. (optional) The PC side, text being typed into Notepad.

## Capturing from a connected device

```bash
ADB=../../../../android-toolchain/sdk/platform-tools/adb.exe   # adjust path
"$ADB" exec-out screencap -p > 01-idle.png
# repeat per screen; then optionally frame them in a device mockup.
```

Keep the status bar clean (full battery/signal) for store-quality shots, or crop it.
