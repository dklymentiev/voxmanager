#!/usr/bin/env bash
# Build the Linux standalone binary -> dist/voxmanager-server
# Run from the server/ directory:  ./packaging/build_linux.sh
# Verified on Ubuntu 24.04 (X11 / XWayland). Runtime needs:
#   - a python3-tk for the pairing window (the build pulls tkinter in):
#       sudo apt install python3-tk
#   - an AppIndicator backend for the tray, e.g. on Debian/Ubuntu:
#       sudo apt install gir1.2-appindicator3-0.1   # or libayatana-appindicator3
#   - an X display. pynput is X11-only, so under a Wayland desktop it runs via
#     XWayland but CANNOT inject keystrokes into Wayland-native windows. For full
#     "type into the active window" support, log into an "Ubuntu on Xorg" session.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 -m PyInstaller \
    --onefile --name voxmanager-server \
    --hidden-import pynput.keyboard._xorg \
    --hidden-import pynput.mouse._xorg \
    --hidden-import pystray._appindicator \
    --hidden-import pystray._xorg \
    --hidden-import PIL.ImageTk \
    --hidden-import PIL._tkinter_finder \
    --distpath dist --workpath build_tmp --noconfirm \
    voxmanager_server.py

echo "Built: dist/voxmanager-server"
