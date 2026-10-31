; ============================================================
;  SuperAutoStudy - Inno Setup 6 installer script
;
;  IMPORTANT: keep this file pure ASCII.
;  (The launcher is launch.cmd - an ASCII-named copy - precisely
;   so that no non-ASCII literal is needed here. The user-facing
;   Chinese-named entry script is picked up by the SuperAutoStudy\*
;   wildcard in [Files] and is never referenced literally.)
;
;  Build: copy this file into dist-portable\ (the folder that contains the
;         SuperAutoStudy\ tree), then run:
;             ISCC.exe _setup.iss
;         Source paths below are relative to the script's own folder.
; ============================================================

#define MyAppName "SuperAutoStudy"
#define MyAppVersion "1.0.16"
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
OutputBaseFilename=SuperAutoStudy-Setup-1.0.16
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
; Release a clean, fully initialized datadir on fresh installs only.
;
; NOTE 1: the Check below must be the cached NeedDataDir() function, NOT a bare
; DirExists('{app}\mysql57\data\mysql'). Inno evaluates Check once PER FILE
; ENTRY, and installing the first file under mysql\ creates that directory, so
; a bare DirExists flips True -> False halfway through and the remaining ~290
; datadir files are silently skipped (mysqld then dies with
; "Table 'mysql.user' doesn't exist"). See [Code] at the end of this file.
;
; NOTE 2: do NOT test DirExists('{app}\mysql57\data') either: this rule has
; createallsubdirs, which creates that directory even when every file under it
; is excluded.
Source: "SuperAutoStudy\mysql57\data\*"; DestDir: "{app}\mysql57\data"; Flags: ignoreversion recursesubdirs createallsubdirs; Check: NeedDataDir
; Overwrite everything else, but keep live database and user settings.
;
; my.ini is deliberately NOT shipped: the copy inside the payload carries the
; packager's absolute basedir/datadir, and mysqld launched with it reads a
; datadir that only exists on the build machine. client/server.py rewrites
; my.ini from the real install root on every start (Services.ensure_my_ini).
Source: "SuperAutoStudy\*"; Excludes: "mysql57\data\*,client\settings.json,my.ini,diag-report.txt,*__pycache__*,*.pyc,mysql57\console.log*,mysql57\mysql-error.log,app\live.log,app\app.log,app\boot.log"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\app.ico"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\app.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent shellexec

[UninstallDelete]
Type: files; Name: "{app}\my.ini"
Type: files; Name: "{app}\app\live.log"
Type: files; Name: "{app}\app\app.log"
Type: files; Name: "{app}\app\boot.log"
Type: files; Name: "{app}\mysql57\mysql-error.log"

[Code]
{ Answer "should we release the bundled MySQL datadir?" exactly once.

  Inno evaluates a Check function once per file entry. The condition we want is
  "the target has no real datadir yet", and installing the very first file under
  mysql\ creates the mysql\ directory - so re-testing it per entry would flip the
  answer to False after one file and skip the other ~290. Cache it instead. }
var
  DataChecked: Boolean;
  DataNeeded: Boolean;

function NeedDataDir(): Boolean;
begin
  if not DataChecked then
  begin
    DataNeeded := not DirExists(ExpandConstant('{app}\mysql57\data\mysql'));
    DataChecked := True;
  end;
  Result := DataNeeded;
end;
