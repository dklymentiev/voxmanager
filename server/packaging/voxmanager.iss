; Inno Setup script for the Vox Manager PC server (per-user install, no admin / UAC).
; Built by packaging/release.ps1, which passes /DMyAppVersion=x.y.z.
; Source layout expected: ..\dist\VoxManager-Server\  (PyInstaller --onedir output)

#define MyAppName "Vox Manager"
#ifndef MyAppVersion
  #define MyAppVersion "0.0.0"
#endif
#define MyAppPublisher "Codim"
#define MyAppURL "https://voxmanager.com"
#define MyAppExeName "VoxManager-Server.exe"

[Setup]
; Stable AppId — never change it, or upgrades become separate installs.
; Regenerated when the product was renamed: reusing the previous GUID would make this
; Setup an in-place upgrade of a pre-rename install, so it would land in that install's
; old directory under {localappdata}\Programs and keep its Add/Remove Programs entry.
AppId={{1A8C68E8-1605-4685-9194-4E753FB66F35}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
; Per-user install: no admin, no UAC prompt. Good fit for an unsigned binary, and it
; keeps the HKCU "Start with Windows" entry (set by the tray app) in the same hive
; that the uninstaller cleans up.
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\{#MyAppExeName}
OutputDir=..\dist
OutputBaseFilename=VoxManager-Setup-{#MyAppVersion}
SetupIconFile=..\app.ico
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Files]
Source: "..\dist\VoxManager-Server\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
; The tray app owns this HKCU Run value ("Start with Windows" checkbox), so the
; installer must NOT create it — but it should clean it up on uninstall.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "Vox Manager"; Flags: dontcreatekey uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName} now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Stop a running tray instance before files are removed.
Filename: "{cmd}"; Parameters: "/C taskkill /IM {#MyAppExeName} /F"; Flags: runhidden; RunOnceId: "KillServer"
