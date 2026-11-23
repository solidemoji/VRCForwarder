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
; ★★ 默认要求管理员：防火墙放行规则（UDP 5000-5020，含「自动搜索」口 5001）**只有管理员模式才会执行**。
;    做成默认非管理员的话，客户装上后规则一条都没有 ⇒ App 点自动搜索永远搜不到
;    （本机回环测试不受防火墙管，开发时测不出来，只有客户机暴露）。
;    仍保留 overrides=dialog：坚持不想提权的用户可以自己选"仅为我安装"，但那样得手动放行防火墙。
; ★★ 强制管理员安装，不再提供「仅为我安装」：
;    非管理员模式下 netsh 无权添加防火墙规则，客户装完会变成「程序在，但一条规则都没有」，
;    表现为 App 搜不到、手输 IP 也不通、接收端一包收不到（客户机实测报障）。
;    宁可不装，也不要装出一个半残的状态。
PrivilegesRequired=admin
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
; 防火墙放行已改为【无条件执行】，不再作为可勾选任务：
;   之前是 checkedonce，客户取消过一次，以后升级都不会再勾上 —— 「重装也修不好」的成因。

[Files]
; 整个 onedir 目录（含 _internal）——必须完整复制
Source: "{#SrcDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[UninstallRun]
; 卸载时移除下面加的规则（名字对不上就静默失败，无副作用）
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#MyAppName} TCP 8080-8090"""; \
    Flags: runhidden
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#MyAppName} UDP 5000-5020"""; \
    Flags: runhidden
; 清理旧版（≤0.0.6）遗留的窄规则
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
; ★★ 必须放行整个 UDP 5000-5020 区间，而不是只放 5000：
;    · 5001 = 「自动搜索」广播应答口 —— 漏了它，App 点自动搜索永远搜不到
;      （本机回环测试不受防火墙影响，所以开发时测不出来，只有客户机才暴露）；
;    · 5002+ = 视频口被占用时自动顺延用的（见 vrc_receiver._candidate_ports）。
; 用**端口级**规则：Windows 首次运行弹窗生成的是"按程序路径"的规则，
; 程序一换目录（重新解压/升级/换安装位置）就失效。
; ★ 无条件执行（已无 Tasks 条件）：装完规则一定会在。
;   旧名先删掉，避免历史版本留下的窄规则（只放 5000/8080）继续占位。
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#MyAppName} UDP 5000"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#MyAppName} MJPEG TCP 8080"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#MyAppName} TCP 8080"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#MyAppName} UDP 5000-5020"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall add rule name=""{#MyAppName} TCP 8080-8090"" dir=in action=allow protocol=TCP localport=8080-8090 profile=any"; \
    Flags: runhidden
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall add rule name=""{#MyAppName} UDP 5000-5020"" dir=in action=allow protocol=UDP localport=5000-5020 profile=any"; \
    Flags: runhidden
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

[Code]
// ★ 装完复查：规则真的加上了才算成功；加不上就明确报错，绝不静默。
//   背景：客户机上「App 搜不到 + 手输 IP 也不通 + 接收端一包没收到」，
//        根因就是规则没加上而安装器毫无提示。
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep = ssPostInstall then
  begin
    if (not Exec(ExpandConstant('{sys}\netsh.exe'),
        'advfirewall firewall show rule name="VRCReceiver UDP 5000-5020"',
        '', SW_HIDE, ewWaitUntilTerminated, ResultCode)) or (ResultCode <> 0) then
    begin
      MsgBox('防火墙放行规则没有添加成功！' + #13#10 + #13#10 +
             '其它设备将无法自动搜索到本机，也无法连接。' + #13#10 + #13#10 +
             '请右键安装包 → 「以管理员身份运行」重新安装；' + #13#10 +
             '或在 Windows 防火墙中手动放行 UDP 5000-5020 与 TCP 8080-8090。',
             mbError, MB_OK);
    end;
  end;
end;
