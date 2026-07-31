# Windows, distribution checklist

Ships the **PC server** (`VoxManager-Server.exe`, built from `../../server/`).

## Two distribution paths

### A) Direct download (recommended first)
- [ ] Build the release exe: `../../server/packaging/build_windows.ps1`
- [ ] **Code-sign** the exe (Authenticode / EV or OV cert) so SmartScreen doesn't
      warn users. Without signing, users see "Windows protected your PC".
- [ ] Host the installer/exe (e.g. on `voxmanager.com`) with a version + checksum.
- [ ] (optional) Wrap in a small installer (Inno Setup) that adds a Start-menu entry
      and optional auto-start.

### B) Microsoft Store (later)
- [ ] Package as **MSIX** (the exe + manifest), pass Store certification.
- [ ] Microsoft Partner Center account (one-time fee).
- More work than direct download; do it after the product has traction.

## Assets (see `listing.md`)
- [ ] Name + short/long description
- [ ] Icon (already generated: `../../server/app.ico`)
- [ ] Screenshots, tray icon, the pairing window, text appearing in an app
- [ ] Privacy policy URL (shared: `../play/privacy-policy.md`)

## Notes
- The server has no telemetry and stores only a local pairing secret in
  `~/.voxmanager/config.json`.
- macOS / Linux builds are scaffolded in `../../server/packaging/` and will get their
  own `store/macos/`, `store/linux/` folders when verified.
