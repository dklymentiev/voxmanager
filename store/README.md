# Store / publishing assets

Everything needed to publish Vox Manager on each distribution channel, one folder per
channel. Keep marketing copy here (versioned) so listings stay consistent with the
product and the brand.

| Channel | Folder | Ships | Status |
|---|---|---|---|
| Google Play | [`play/`](play/) | Android app (`com.voxmanager`) | drafting |
| Windows | [`windows/`](windows/) | PC server (direct download / Microsoft Store) | drafting |
| macOS | `macos/` (later) | PC server (.app / Mac App Store) | planned |
| Linux | `linux/` (later) | PC server (Snap/Flatpak/direct) | planned |

## Conventions

- **Brand:** name **Vox Manager**, descriptor **Voice to PC**. Store title pattern:
  "Vox Manager, Voice to PC". Colors/icon come from [`../brand/`](../brand/).
- **Copy** lives in `*/listing.md` (title, descriptions, what's-new).
- **Images** go in `*/graphics/` and `*/screenshots/`: each has a README with the
  exact sizes each store requires. Binary art is git-ignored by default; commit
  finals deliberately.
- **Privacy policy** ([`play/privacy-policy.md`](play/privacy-policy.md)) is shared
  across channels; it must be hosted at a public URL for Google Play.

## What's still needed before submitting

- A public **privacy-policy URL** (host the markdown, e.g. on `voxmanager.com`).
- **Release signing** for the Android app (see [`../docs/ROADMAP.md`](../docs/ROADMAP.md)).
- **Code signing** for the Windows server executable.
- Final **screenshots** (can be captured from the running app, see each
  `screenshots/README.md`).
