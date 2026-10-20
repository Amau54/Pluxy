#define AppVersion "1.8.32"
[Setup]
AppId={{42602BFC-1295-4EA4-B419-808A2A4D09C0}
AppName=Pluxy
AppVersion={#AppVersion}
AppPublisher=Pluxy
AppPublisherURL=https://github.com/Amau54/Pluxy
DefaultDirName={autopf}\Pluxy
DisableDirPage=no
DefaultGroupName=Pluxy
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
WizardStyle=modern dark includetitlebar
WizardSizePercent=110
SetupIconFile=..\assets\pluxy.ico
UninstallDisplayIcon={app}\Pluxy.exe
OutputDir={#BuildOutput}
OutputBaseFilename=Pluxy-Setup
Compression=lzma2
SolidCompression=yes
DisableWelcomePage=no
DisableReadyPage=yes
CloseApplications=yes
RestartApplications=no
Uninstallable=yes
VersionInfoDescription=Installation de Pluxy

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Messages]
WelcomeLabel2=Installez Pluxy pour lire les médias d'un serveur ou partager les dossiers de ce PC.%n%nVous choisirez le mode au premier lancement. Vos réglages et vos fichiers sont conservés lors des mises à jour.

[Tasks]
Name: "desktopicon"; Description: "Créer un raccourci sur le bureau"; Flags: unchecked

[Files]
Source: "{#Payload}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: ".venv\*,*.pyc,__pycache__\*"
Source: "{#Bootstrapper}"; Flags: dontcopy

[Icons]
Name: "{group}\Pluxy"; Filename: "{app}\Pluxy.exe"
Name: "{autodesktop}\Pluxy"; Filename: "{app}\Pluxy.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Pluxy.exe"; Description: "Ouvrir Pluxy"; Flags: nowait postinstall skipifsilent runasoriginaluser
Filename: "{app}\Pluxy.exe"; Flags: nowait runasoriginaluser; Check: IsAutomaticUpdate

[Code]
function IsAutomaticUpdate(): Boolean;
begin
  Result := WizardSilent and (ExpandConstant('{param:PLUXYUPDATE|0}') = '1');
end;

function WebViewInstalled(): Boolean;
var Version: String;
begin
  Result := (RegQueryStringValue(HKLM32, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version) and (Version <> '') and (Version <> '0.0.0.0')) or
    (RegQueryStringValue(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version) and (Version <> '') and (Version <> '0.0.0.0'));
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var Code: Integer;
begin
  Result := '';
  if not WebViewInstalled() then begin
    WizardForm.StatusLabel.Caption := 'Préparation du composant d''affichage Microsoft…';
    ExtractTemporaryFile('MicrosoftEdgeWebview2Setup.exe');
    if not Exec(ExpandConstant('{tmp}\MicrosoftEdgeWebview2Setup.exe'), '/silent /install', '', SW_HIDE, ewWaitUntilTerminated, Code) then begin
      Result := 'Le composant d''affichage n''a pas pu être installé. Vérifiez votre connexion Internet et relancez l''installation.';
      exit;
    end;
    if not WebViewInstalled() then Result := 'Le composant d''affichage Microsoft reste indisponible. Vérifiez votre connexion Internet et relancez l''installation.';
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var Value: String;
begin
  if CurUninstallStep = usUninstall then begin
    if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Pluxy', Value) and
      (Pos(Lowercase(ExpandConstant('{app}\Pluxy.exe')), Lowercase(Value)) > 0) then
      RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Pluxy');
  end;
end;
