; ============================================================
;  SuperAutoStudy - Inno Setup 6 installer script
;
;  IMPORTANT: keep this file pure ASCII.
;  (The launcher is launch.cmd - an ASCII-named copy - precisely
;   so that no non-ASCII literal is needed here. The user-facing
;   Chinese-named entry script is picked up by the SuperAutoStudy\*
;   wildcard in [Files] and is never referenced literally.)
;
;  Build:  ISCC.exe _setup.iss
; ============================================================

#define MyAppName "SuperAutoStudy"
#define MyAppVersion "1.0.4"
#define MyAppPublisher "SuperAutoStudy"
#define MyAppExeName "launch.cmd"

[Setup]
; {{ is an escaped literal { for AppId
AppId={{DDB7F142-00BF-421F-A21D-85E3782A97A0}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
AllowNoIcons=yes
; Install per-user, no admin prompt needed.
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=SuperAutoStudy-Setup-1.0.4
SetupIconFile=SuperAutoStudy\app.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\app.ico
MinVersion=10.0
SetupLogging=yes

[Languages]
Name: "chinese"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce

[Files]
; Overwrite everything, but keep live database and user settings
Source: "SuperAutoStudy\*"; Excludes: "mysql57\data\*,client\settings.json"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Release clean initialized datadir only on fresh install
; (datadir missing). Upgrades fully keep queue, login and params
Source: "SuperAutoStudy\mysql57\data\*"; DestDir: "{app}\mysql57\data"; Flags: ignoreversion recursesubdirs createallsubdirs; Check: not DirExists(ExpandConstant('{app}\mysql57\data'))

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\app.ico"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\app.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent shellexec

[UninstallDelete]
Type: files; Name: "{app}\my.ini"
Type: files; Name: "{app}\app\live.log"
Type: files; Name: "{app}\app\app.log"
Type: files; Name: "{app}\mysql57\mysql-error.log"
