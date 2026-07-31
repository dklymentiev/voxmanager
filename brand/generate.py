#!/usr/bin/env python3
"""Brand token generator -- the ONLY thing allowed to write the brand outputs.

Source of truth:  brand/tokens.json
Generated:
  app/src/main/res/values/colors.xml   (Android, build-time tokens)
  pc_server/brand_tokens.py            (server + tray/pairing window read this)
  brand/brandbook.html                 (human-facing reference, regenerated)

Usage:  python brand/generate.py        # from the repo root or anywhere
Edit tokens.json, rerun this, commit. Never hand-edit the generated files.
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOKENS = ROOT / "brand" / "tokens.json"
OUT_COLORS = ROOT / "android" / "app" / "src" / "main" / "res" / "values" / "colors.xml"
OUT_PY = ROOT / "server" / "brand_tokens.py"
OUT_HTML = ROOT / "brand" / "brandbook.html"

BANNER = "AUTO-GENERATED from brand/tokens.json by brand/generate.py. Do not hand-edit."


def load():
    return json.loads(TOKENS.read_text(encoding="utf-8"))


# ----------------------------------------------------------------- colors.xml
def gen_colors_xml(t):
    color = t["color"]
    alpha = t.get("android_alpha", {})
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f"<!-- {BANNER} -->",
             "<resources>"]
    for res_name, token in t["android_colors"].items():
        hex6 = color[token].lstrip("#")
        if token in alpha:                       # opaque ARGB for white/black
            value = f"#{alpha[token]}{hex6}"
        else:
            value = f"#{hex6}"
        lines.append(f'    <color name="{res_name}">{value}</color>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------- brand_tokens.py
def gen_py(t):
    color = t["color"]
    grad = t["gradient"]
    font = t["font"]
    # The SPDX header is emitted here rather than added to the generated file:
    # brand_tokens.py is overwritten on every run, so a hand-added header would
    # silently disappear the next time anyone edits tokens.json.
    out = ['# SPDX-License-Identifier: GPL-3.0-or-later',
           '# Copyright (C) 2026 Dmytro Klymentiev',
           f'# {BANNER}',
           '"""Vox Manager brand tokens for the PC server (tray + pairing window)."""',
           "",
           f'NAME = {t["name"]!r}',
           f'TAGLINE = {t["tagline"]!r}',
           f'LISTING = {t["listing"]!r}',
           f'VERSION = {t["version"]!r}',
           "",
           "",
           "class C:",
           '    """Hex color tokens. Use as C.ACCENT, C.BG, ..."""']
    for k, v in color.items():
        out.append(f"    {k.upper()} = {v!r}")
    out += ["",
            "",
            f"GRADIENT_BRAND = {tuple(grad['brand'])!r}",
            f"GRADIENT_AURORA = {tuple(grad['aurora'])!r}",
            "",
            f"RADIUS = {t['radius']!r}",
            "",
            f"FONT = {font['windows']!r}",
            f"FONT_SEMIBOLD = {font['windows_semibold']!r}",
            f"FONT_MONO = {font['mono']!r}"]
    return "\n".join(out) + "\n"


# ----------------------------------------------------------------- brandbook.html
def gen_html(t):
    color = t["color"]
    aurora = ",".join(t["gradient"]["aurora"])
    brand_grad = ",".join(t["gradient"]["brand"])
    swatches = "\n".join(
        f'      <div class="cell"><div class="sw" style="background:{v}"></div>'
        f'<div class="nm">{k}</div><div class="hex">{v}</div></div>'
        for k, v in color.items())

    # Inline the master icon (concept A: gradient mic on a dark tile). Strip the
    # XML prolog and the fixed width/height so CSS can size each copy.
    icon_svg = (ROOT / "brand" / "icon" / "aurora-mic.svg").read_text(encoding="utf-8")
    icon_inline = icon_svg[icon_svg.find("<svg"):].replace('width="512" height="512" ', "")

    return f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>{t['name']} -- Brand Book</title>
<!-- {BANNER} -->
<style>
  :root{{ --bg:{color['bg']}; --surface:{color['surface']}; --ink:{color['ink']};
         --muted:{color['muted']}; }}
  *{{box-sizing:border-box;margin:0;padding:0}}
  body{{font-family:"{t['font']['windows']}",-apple-system,system-ui,sans-serif;
        background:var(--bg);color:var(--ink);padding:56px 24px 96px}}
  .wrap{{max-width:1040px;margin:0 auto}}
  h1{{font-size:46px;font-weight:800;letter-spacing:-1px}}
  .grad{{background:linear-gradient(90deg,{aurora});-webkit-background-clip:text;
         background-clip:text;color:transparent}}
  .tag{{color:var(--muted);font-size:20px;margin-top:10px}}
  .listing{{color:var(--muted);font-size:14px;margin-top:4px;font-style:italic}}
  h2{{font-size:12px;letter-spacing:2px;text-transform:uppercase;color:var(--muted);
      margin:56px 0 18px;border-bottom:1px solid #262642;padding-bottom:10px}}
  .grid{{display:flex;flex-wrap:wrap;gap:18px}}
  .cell{{width:150px}}
  .sw{{height:80px;border-radius:14px;border:1px solid rgba(255,255,255,.08)}}
  .nm{{margin-top:8px;font-weight:600;font-size:13px}}
  .hex{{color:var(--muted);font-size:12px}}
  .gradbar{{height:64px;border-radius:14px;background:linear-gradient(90deg,{brand_grad})}}
  .icons{{display:flex;gap:30px;align-items:flex-end}}
  .iconbox{{display:flex;flex-direction:column;align-items:center;gap:10px;
            color:var(--muted);font-size:12px}}
  .iconbox svg{{display:block;border-radius:22%}}
  .s112 svg{{width:112px;height:112px}}
  .s72 svg{{width:72px;height:72px}}
  .s44 svg{{width:44px;height:44px}}
  .ui{{display:flex;gap:22px;align-items:center;flex-wrap:wrap}}
  .ui-card{{background:var(--surface);border-radius:16px;padding:18px 26px}}
  .ui-code{{font-size:28px;font-weight:700;letter-spacing:8px;color:var(--ink)}}
  .ui-btn{{border:0;border-radius:24px;padding:13px 30px;color:#fff;font-weight:600;
           font-size:14px;background:linear-gradient(90deg,{brand_grad})}}
  .ui-chip{{display:inline-flex;align-items:center;gap:8px;background:var(--surface);
            border-radius:999px;padding:8px 16px;color:var(--ink);font-size:13px}}
  .dot{{width:9px;height:9px;border-radius:50%;background:{color['accent_2']}}}
  .ver{{margin-top:64px;color:var(--muted);font-size:12px}}
</style></head>
<body><div class="wrap">
  <h1 class="grad">{t['name']}</h1>
  <div class="tag">{t['tagline']}</div>
  <div class="listing">Store listing: {t['listing']}</div>

  <h2>Color</h2>
  <div class="grid">
{swatches}
  </div>

  <h2>Brand gradient (violet -> green)</h2>
  <div class="gradbar"></div>

  <h2>Icon — Aurora Mic</h2>
  <div class="icons">
    <div class="iconbox s112">{icon_inline}<div>112 px</div></div>
    <div class="iconbox s72">{icon_inline}<div>72 px</div></div>
    <div class="iconbox s44">{icon_inline}<div>44 px</div></div>
  </div>
  <p style="color:var(--muted);font-size:13px;margin-top:14px">
    Master: <b>brand/icon/aurora-mic.svg</b>. Same mark on the Android launcher and
    the PC tray/pairing window — gradient mic on a dark brand tile.</p>

  <h2>UI elements</h2>
  <div class="ui">
    <div class="ui-card"><div class="ui-code">4&nbsp;8&nbsp;2&nbsp;9&nbsp;1&nbsp;5</div></div>
    <button class="ui-btn">New code</button>
    <span class="ui-chip"><span class="dot"></span>Connected</span>
  </div>

  <h2>Type</h2>
  <p>Windows UI: <b>{t['font']['windows']}</b> / <b>{t['font']['windows_semibold']}</b>
     &nbsp;|&nbsp; Mono (codes): <b>{t['font']['mono']}</b>
     &nbsp;|&nbsp; Android: <b>{t['font']['android']}</b></p>

  <div class="ver">v{t['version']} -- generated from brand/tokens.json. Do not hand-edit.</div>
</div></body></html>
"""


def main():
    t = load()
    OUT_COLORS.write_text(gen_colors_xml(t), encoding="utf-8")
    OUT_PY.write_text(gen_py(t), encoding="utf-8")
    OUT_HTML.write_text(gen_html(t), encoding="utf-8")
    print(f"OK  {t['name']} v{t['version']}")
    print(f"  -> {OUT_COLORS.relative_to(ROOT)}")
    print(f"  -> {OUT_PY.relative_to(ROOT)}")
    print(f"  -> {OUT_HTML.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
