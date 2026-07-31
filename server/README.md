# Vox Manager, PC server

Cross-platform Python server: receives signed text from the phone and types it into
the active window. The code is OS-agnostic (`pynput` / `pystray` / `tkinter`);
Windows-only niceties (DPI awareness, dark title bar, taskbar identity) are guarded
behind `sys.platform` and are no-ops elsewhere.

## Run from source

```bash
pip install -r requirements.txt
python voxmanager_server.py            # tray, headless (production)
python voxmanager_server.py --console  # console + prints a pairing code (debug)
python voxmanager_server.py --pair     # print a code and exit
python voxmanager_server.py --reset    # rotate the secret (all phones must re-pair)
```

Health: the phone discovers the server by UDP broadcast on port **8767**; the HTTP
port auto-falls back from 8765 if busy.

## Release build (Windows)

Building needs the packaging tools on top of the runtime ones:

```powershell
pip install -r requirements.txt -r requirements-build.txt
```

`packaging/release.ps1` is the one-shot pipeline: **run tests → build exe → compile
installer**. A red test aborts the build, so a broken server can never be packaged.

```powershell
.\packaging\release.ps1            # tests + onedir exe + installer (version from android versionName)
.\packaging\release.ps1 -Version 1.2.0
.\packaging\release.ps1 -Portable  # also emit a single-file VoxManager-Server-portable.exe
.\packaging\release.ps1 -NoInstaller
```

Artifacts land in `dist/`:

| Artifact | What |
|---|---|
| `VoxManager-Setup-<ver>.exe` | **the installer**: per-user, no admin/UAC; installs to `%LOCALAPPDATA%\Programs\Vox Manager` |
| `VoxManager-Server\` | the unpacked `--onedir` app the installer bundles |
| `VoxManager-Server-portable.exe` | optional single-file build (`-Portable`) |

Needs **Inno Setup 6** for the installer step: `winget install --id JRSoftware.InnoSetup -e`
(the installer `.iss` is `packaging/voxmanager.iss`). The build is **unsigned**: Windows
SmartScreen will warn until the binary is code-signed.

### Quick / other-OS builds

| OS | Script | Output |
|---|---|---|
| Windows | `packaging/build_windows.ps1` | `dist/VoxManager-Server.exe` (quick single-file, no installer) |
| macOS | `packaging/build_macos.sh` | `dist/Vox Manager Server.app` |
| Linux | `packaging/build_linux.sh` | `dist/voxmanager-server` |

> macOS / Linux builds are scripted but **not yet verified on real hardware**: they
> need a Mac and a Linux box to test packaging + tray behaviour. Windows is verified.

## Files

| File | Purpose |
|---|---|
| `voxmanager_server.py` | the whole server (HTTP + discovery + tray + pairing window) |
| `brand_tokens.py` | colors/fonts, **generated** from `../brand/tokens.json` (do not edit) |
| `app.ico` | Windows exe icon, **generated** from the brand mic |

## Internal identifiers

The config dir `~/.voxmanager` and the `VOXMANAGER_*` discovery tokens are invisible
to users, but they are part of the wire contract: the phone matches the discovery
magic byte for byte, and the config dir is where the machine secret lives. Renaming
either one forces every paired phone to pair again, and the two halves ship
separately, so a rename has to land in the app and the server together. Change them
only for a good reason, and treat it as a breaking release.
