#!/usr/bin/env bash
# Build the macOS app bundle -> dist/Vox Manager Server.app
# Run from the server/ directory:  ./packaging/build_macos.sh
# NOTE: not yet verified on real hardware. Needs a Mac with Python 3 + the deps.
#   After install: grant the app Accessibility + Input Monitoring (System Settings
#   -> Privacy & Security) so it can type into other apps.
set -euo pipefail
cd "$(dirname "$0")/.."

# Optional: convert app.ico -> app.icns if iconutil/sips available (best-effort).
ICON_ARG=()
if [[ -f app.icns ]]; then ICON_ARG=(--icon app.icns); fi

python3 -m PyInstaller \
    --windowed --onefile --name "Vox Manager Server" \
    "${ICON_ARG[@]}" \
    --hidden-import pynput.keyboard._darwin \
    --hidden-import pystray._darwin \
    --hidden-import PIL.ImageTk \
    --distpath dist --workpath build_tmp --noconfirm \
    voxmanager_server.py

echo "Built: dist/Vox Manager Server.app"
