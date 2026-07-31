<#
  release.ps1 - one-shot Windows release pipeline for the Vox Manager PC server.

    1. Run the server test suite        (gate: a red test aborts the build)
    2. Build the tray exe (PyInstaller, --onedir)
    3. Compile the installer (Inno Setup)  -> dist\VoxManager-Setup-<ver>.exe
    4. (optional) also build a portable single-file exe

  Usage (from anywhere):
    .\packaging\release.ps1                 # tests + exe + installer, version from android versionName
    .\packaging\release.ps1 -Version 1.2.0
    .\packaging\release.ps1 -SkipTests      # NOT for releases; debugging only
    .\packaging\release.ps1 -NoInstaller    # exe only (skip Inno Setup)
    .\packaging\release.ps1 -Portable       # also emit VoxManager-Server-portable.exe

  Exit codes: 0 ok | 1 tests failed | 2 toolchain missing | 3 build failed
#>
[CmdletBinding()]
param(
    [string]$Version,
    [string]$DownloadBaseUrl = "https://voxmanager.com/download",
    [switch]$SkipTests,
    [switch]$NoInstaller,
    [switch]$Portable
)

$ErrorActionPreference = 'Stop'
$server = Resolve-Path (Join-Path $PSScriptRoot '..')
$root   = Resolve-Path (Join-Path $server '..')

function Say([string]$m)  { Write-Host $m -ForegroundColor Cyan }
function OK([string]$m)   { Write-Host "[OK]    $m" -ForegroundColor Green }
function Info([string]$m) { Write-Host "        $m" -ForegroundColor Gray }
function Die([int]$code, [string]$m) { Write-Host "[ERROR] $m" -ForegroundColor Red; exit $code }

# ---- version -------------------------------------------------------------
if (-not $Version) {
    $gradle = Get-ChildItem (Join-Path $root 'android\app') -Filter 'build.gradle*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($gradle) {
        $m = Select-String -Path $gradle.FullName -Pattern 'versionName\s*=?\s*"([^"]+)"' | Select-Object -First 1
        if ($m) { $Version = $m.Matches[0].Groups[1].Value }
    }
}
if (-not $Version) { Die 2 "Could not determine version. Pass -Version x.y.z" }
Say "=== Vox Manager release  v$Version ==="

# APP_VERSION in the server is what the built exe reports to the updater; it must
# match the release version, or clients would compare against the wrong number.
$avMatch = Select-String -Path (Join-Path $server 'voxmanager_server.py') -Pattern 'APP_VERSION\s*=\s*"([^"]+)"' | Select-Object -First 1
if ($avMatch) {
    $appVer = $avMatch.Matches[0].Groups[1].Value
    if ($appVer -ne $Version) { Die 2 "APP_VERSION ($appVer) in voxmanager_server.py != release version ($Version) - update it first." }
    OK "APP_VERSION matches ($appVer)"
} else {
    Write-Host "[WARN]  APP_VERSION not found in voxmanager_server.py" -ForegroundColor Yellow
}

# ---- 1. tests ------------------------------------------------------------
if ($SkipTests) {
    Write-Host "[WARN]  tests skipped (-SkipTests) - do NOT ship this" -ForegroundColor Yellow
} else {
    Say "[1/4] tests"
    Push-Location $root
    try {
        # Discover, not one file: a new test module must gate the release too.
        & python -m unittest discover -s (Join-Path $root 'tests')
        if ($LASTEXITCODE -ne 0) { Die 1 "tests failed (exit $LASTEXITCODE) - build aborted" }
    } finally { Pop-Location }
    OK "tests passed"
}

# ---- 2. build exe (onedir) ----------------------------------------------
Say "[2/4] build exe (PyInstaller --onedir)"
Push-Location $server
try {
    # A running tray instance locks its own files; stop it BEFORE cleaning dist.
    Get-Process -Name 'VoxManager-Server' -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 400
    if (Test-Path 'dist\VoxManager-Server') { Remove-Item 'dist\VoxManager-Server' -Recurse -Force }

    $icon = Join-Path $server 'app.ico'   # absolute: --specpath would otherwise resolve it under build_tmp
    & python -m PyInstaller --noconsole --name VoxManager-Server --icon $icon `
        --hidden-import qrcode `
        --hidden-import pynput.keyboard._win32 `
        --hidden-import pystray._win32 `
        --hidden-import PIL.ImageTk `
        --distpath dist --workpath build_tmp --specpath build_tmp --noconfirm `
        voxmanager_server.py
    if ($LASTEXITCODE -ne 0) { Die 3 "PyInstaller failed (exit $LASTEXITCODE)" }
    if (-not (Test-Path 'dist\VoxManager-Server\VoxManager-Server.exe')) { Die 3 "exe not produced" }
    OK "dist\VoxManager-Server\ (onedir)"

    if ($Portable) {
        Say "      portable single-file build"
        & python -m PyInstaller --noconsole --onefile --name VoxManager-Server-portable --icon $icon `
            --hidden-import qrcode `
        --hidden-import pynput.keyboard._win32 `
            --hidden-import pystray._win32 `
            --hidden-import PIL.ImageTk `
            --distpath dist --workpath build_tmp --specpath build_tmp --noconfirm `
            voxmanager_server.py
        if ($LASTEXITCODE -ne 0) { Die 3 "portable PyInstaller failed (exit $LASTEXITCODE)" }
        OK "dist\VoxManager-Server-portable.exe"
    }
} finally { Pop-Location }

# ---- 3. installer (Inno Setup) ------------------------------------------
if ($NoInstaller) {
    Write-Host "[WARN]  installer skipped (-NoInstaller)" -ForegroundColor Yellow
} else {
    Say "[3/4] installer (Inno Setup)"
    $iscc = @(
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles}\Inno Setup 6\ISCC.exe",
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"   # winget per-user install
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $iscc) { $iscc = (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source }
    if (-not $iscc) {
        Die 2 ("Inno Setup not found. Install it, then re-run:`n" +
               "        winget install --id JRSoftware.InnoSetup -e`n" +
               "        (or https://jrsoftware.org/isdl.php)")
    }
    Info "using $iscc"
    Push-Location $server
    try {
        & $iscc "/DMyAppVersion=$Version" 'packaging\voxmanager.iss'
        if ($LASTEXITCODE -ne 0) { Die 3 "Inno Setup failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
    OK "dist\VoxManager-Setup-$Version.exe"

    # Update manifest the server polls. Upload this next to the installer at
    # $DownloadBaseUrl so existing installs can find and verify the new build.
    $setupPath = Join-Path $server "dist\VoxManager-Setup-$Version.exe"
    $sha = (Get-FileHash $setupPath -Algorithm SHA256).Hash.ToLower()
    $manifest = [ordered]@{
        version = $Version
        url     = "$DownloadBaseUrl/VoxManager-Setup-$Version.exe"
        sha256  = $sha
        notes   = "Vox Manager $Version"
    }
    $manifest | ConvertTo-Json | Set-Content (Join-Path $server 'dist\latest.json') -Encoding utf8
    OK "dist\latest.json  ->  $($manifest.url)"
}

# ---- 4. summary ----------------------------------------------------------
Say "[4/4] artifacts"
$dist = Join-Path $server 'dist'
Get-ChildItem $dist -ErrorAction SilentlyContinue |
    ForEach-Object {
        if ($_.PSIsContainer) {
            $sz = (Get-ChildItem $_.FullName -Recurse -File | Measure-Object Length -Sum).Sum
            Info ("{0,-34} {1,8:N1} MB  (folder)" -f $_.Name, ($sz / 1MB))
        } else {
            Info ("{0,-34} {1,8:N1} MB" -f $_.Name, ($_.Length / 1MB))
        }
    }
Write-Host ""
OK "release v$Version complete"
exit 0
