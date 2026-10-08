; VRCReceiver 安装包脚本 (Inno Setup 6)
;
; 用途：把 PyInstaller 打包的 onedir 产物做成带向导的 Windows 安装包，
;      用户可自选安装目录、创建桌面/开始菜单快捷方式、可选开机自启。
;
; 编译：ISCC.exe VRCReceiver.iss
; 产物：Output\VRCReceiver-Setup-x.y.z.exe

#define MyAppName "VRCReceiver"
#ifndef MyAppVersion
  #define MyAppVersion "0.0.6"
#endif
#define MyAppPublisher "solidemoji"
#define MyAppURL "https://github.com/solidemoji/VRCForwarder"
#define MyAppExeName "VRCReceiver.exe"
; PyInstaller onedir 产物目录（含 VRCReceiver.exe 与 _internal）
#define SrcDir "E:\UVCReceiver\dist\VRCReceiver"

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
SetupIconFile=E:\UVCReceiver\VRCReceiver.ico
; 许可协议页（可选：指向仓库 LICENSE）
; LicenseFile=E:\VRCForwarder\LICENSE
OutputDir=E:\UVCReceiver\installer\Output
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
; 允许局域网内其它设备/软件访问拉流地址（仅在管理员安装模式下可用）
; ★ 务必用**端口级**规则：Windows 首次运行弹窗生成的是"按程序路径"的规则，
;   程序一换目录（重新解压/升级/换安装位置）就失效 —— 这是"接收端能看画面、
;   但其它设备拉不到"的常见原因。
Name: "firewall"; Description: "允许局域网内其它设备访问拉流地址（添加防火墙放行规则）"; \
    GroupDescription: "其他选项:"; Flags: checkedonce; Check: IsAdminInstallMode

[Files]
; 整个 onedir 目录（含 _internal）——必须完整复制
Source: "{#SrcDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[UninstallRun]
; 卸载时移除上面加的规则（名字对不上就静默失败，无副作用）
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#MyAppName} MJPEG TCP 8080"""; \
    Flags: runhidden
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#MyAppName} UDP 5000"""; \
    Flags: runhidden

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
; 防火墙放行（仅管理员安装模式 + 勾选该任务时执行）
; 用**端口级**规则：Windows 首次运行弹窗生成的是"按程序路径"的规则，
; 程序一换目录（重新解压/升级/换安装位置）就失效
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall add rule name=""{#MyAppName} MJPEG TCP 8080"" dir=in action=allow protocol=TCP localport=8080 profile=any"; \
    Flags: runhidden; Tasks: firewall
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall add rule name=""{#MyAppName} UDP 5000"" dir=in action=allow protocol=UDP localport=5000 profile=any"; \
    Flags: runhidden; Tasks: firewall
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
