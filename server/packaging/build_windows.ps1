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

# PowerShell has no `set -e`, so without this check the script prints "Built" even
# when PyInstaller died. It does die in one very ordinary case: the previous build is
# still running and holds the exe open, and WinError 5 then reads as success.
if ($LASTEXITCODE -ne 0 -or -not (Test-Path 'dist/VoxManager-Server.exe')) {
    Write-Error "Build FAILED (exit $LASTEXITCODE). If it was a permission error on the exe, the previous build is probably still running: stop it and retry."
    exit 1
}
Write-Output "Built: dist/VoxManager-Server.exe"
