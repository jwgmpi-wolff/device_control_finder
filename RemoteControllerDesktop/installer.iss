#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef PublishDir
  #error "PublishDir must point to the self-contained dotnet publish output."
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif

[Setup]
AppId={{E838BD05-B630-42BA-B7CB-A07BCA89C3D0}
AppName=RemoteControllerDesktop
AppVersion={#AppVersion}
DefaultDirName={autopf}\RemoteControllerDesktop
DefaultGroupName=RemoteControllerDesktop
OutputDir={#OutputDir}\downloads
OutputBaseFilename=RemoteControllerDesktop-Setup
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=lowest
UninstallDisplayIcon={app}\RemoteControllerDesktop.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\RemoteControllerDesktop"; Filename: "{app}\RemoteControllerDesktop.exe"
Name: "{autodesktop}\RemoteControllerDesktop"; Filename: "{app}\RemoteControllerDesktop.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"

[Run]
Filename: "{app}\RemoteControllerDesktop.exe"; Description: "Launch RemoteControllerDesktop"; Flags: postinstall nowait skipifsilent
