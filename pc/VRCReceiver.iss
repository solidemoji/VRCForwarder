; VRCReceiver 安装包脚本 (Inno Setup 6)
;
; 用途：把 PyInstaller 打包的 onedir 产物做成带向导的 Windows 安装包，
;      用户可自选安装目录、创建桌面/开始菜单快捷方式、可选开机自启。
;
; 编译（在 pc/ 目录下）：ISCC.exe VRCReceiver.iss
;   前置：先跑 pyinstaller 生成 dist\VRCReceiver
; 产物：Output\VRCReceiver-Setup-x.y.z.exe

#define MyAppName "VRCReceiver"
#define MyAppVersion "0.0.1"
#define MyAppPublisher "solidemoji"
#define MyAppURL "https://github.com/solidemoji/VRCForwarder"
#define MyAppExeName "VRCReceiver.exe"
; PyInstaller onedir 产物目录（含 VRCReceiver.exe 与 _internal）
; 构建前先在本目录执行:  pyinstaller VRCReceiver.spec --noconfirm --clean
#define SrcDir "dist\VRCReceiver"

[Setup]
AppId={{8F3C2A41-7B5E-4D9A-9C21-6E4B8D0F3A57}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}/releases
; 默认安装目录（用户可在向导中改）
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=no
AllowNoIcons=yes
; 卸载信息
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\{#MyAppExeName}
; 压缩：lzma2/max 体积最小（103.7MB 目录 -> 约 40MB 安装包）
Compression=lzma2/max
SolidCompression=yes
; 需要 64 位 Windows（PyInstaller 产物为 win64）
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; 无需管理员权限：默认装到用户目录，避免 UAC（也可用 admin 模式装到 Program Files）
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
; 向导界面
WizardStyle=modern
; 图标
SetupIconFile=VRCReceiver.ico
; 许可协议页（可选）：取消下面注释并在向导第二页展示协议。
; 仓库结构下 LICENSE 在仓库根目录，相对本文件为 ..\LICENSE
; LicenseFile=..\LICENSE
OutputDir=installer\Output
OutputBaseFilename=VRCReceiver-Setup-{#MyAppVersion}

[Languages]
Name: "chinese"; MessagesFile: "compiler:Default.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
; 桌面快捷方式（可选，默认勾选）
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; \
    GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce
; 开机自启（可选，默认不勾）——接收端常驻使用时可勾
Name: "autostart"; Description: "开机自动启动 {#MyAppName}（常驻接收推流时勾选）"; \
    GroupDescription: "其他选项:"; Flags: unchecked

[Files]
; 整个 onedir 目录（含 _internal）——必须完整复制
Source: "{#SrcDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; 开始菜单
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; \
    IconFilename: "{app}\{#MyAppExeName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
; 桌面（按勾选）
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; \
    Tasks: desktopicon

[Registry]
; 开机自启（按勾选，写 HKCU 避免管理员权限）
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
    ValueType: string; ValueName: "{#MyAppName}"; \
    ValueData: """{app}\{#MyAppExeName}"""; \
    Flags: uninsdeletevalue; Tasks: autostart

[Run]
; 安装完成后可选“立即运行”
Filename: "{app}\{#MyAppExeName}"; \
    Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; \
    Flags: nowait postinstall skipifsilent

[UninstallDelete]
; 清理运行期可能生成的日志（保留用户数据则不要删目录本身）
Type: filesandordirs; Name: "{app}\__pycache__"
Type: files; Name: "{app}\*.log"

[Messages]
; 自定义提示（中文环境更友好）
chinese.FinishedLabel={#MyAppName} 已安装完成。%n%n程序目录：%1
english.FinishedLabel={#MyAppName} has been installed.%n%nInstall folder: %1
