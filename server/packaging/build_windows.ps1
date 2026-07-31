# Build the Windows standalone tray executable -> dist/VoxManager-Server.exe
# Run from anywhere:  ./packaging/build_windows.ps1
# (PyInstaller logs to stderr; don't treat that as a failure.)
Set-Location (Join-Path $PSScriptRoot "..")

python -m PyInstaller `
    --noconsole --onefile --name VoxManager-Server --icon app.ico `
    --hidden-import pynput.keyboard._win32 `
    --hidden-import pystray._win32 `
    --hidden-import PIL.ImageTk `
    --distpath dist --workpath build_tmp --noconfirm `
    voxmanager_server.py

Write-Output "Built: dist/VoxManager-Server.exe"
