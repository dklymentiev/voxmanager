# Vox Manager, Brand

**Brand name:** Vox Manager &nbsp;|&nbsp; **Descriptor / ASO keywords:** Voice to PC
**Store listing:** _Vox Manager, Voice to PC dictation_

`tokens.json` is the **single source of truth** for colors, gradients, radii and
fonts. Everything else here is generated from it, never hand-edit the generated
files (they carry an AUTO-GENERATED banner).

## Layout

```
brand/
  tokens.json          # SOURCE OF TRUTH, edit this
  generate.py          # tokens.json -> the 3 generated outputs below
  brandbook.html       # GENERATED, visual reference (open in a browser)
  icon/aurora-mic.svg  # master vector for the icon (tray .ico + Android launcher)
  README.md            # this file
```

## Generated outputs (do not edit by hand)

| File | Consumed by |
|---|---|
| `app/src/main/res/values/colors.xml` | Android app, at build time |
| `pc_server/brand_tokens.py` | PC server tray + pairing window, at runtime |
| `brand/brandbook.html` | humans |

## Workflow

1. Edit `brand/tokens.json`.
2. Run `python brand/generate.py`.
3. Review the diff, rebuild what changed (APK / server exe), commit `tokens.json`
   **and** the generated files together so the tree stays consistent.

## Notes

- Canonical palette was seeded from the live Android `colors.xml` so regenerating
  does not change the app's appearance. `colors.xml` resource names are preserved.
- `white`/`black` are emitted as opaque ARGB (`#FFFFFFFF` / `#FF000000`) for Android
  via `android_alpha` in tokens.json; every other color is 6-digit hex.
- The tray/pairing window's runtime image is still drawn in code
  (`make_tray_image`) but pulls its colors from `brand_tokens.py`. The SVG is the
  design master; if you change the mic shape, update both.
