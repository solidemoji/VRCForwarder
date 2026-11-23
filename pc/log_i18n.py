# -*- coding: utf-8 -*-
"""log_i18n.py — 远端安装/卸载脚本日志的翻译

安装脚本跑在头显上，输出是中文技术日志；本机界面按所选语言显示：
  · 中文界面      → 原样输出
  · 其它语言      → 按下表翻成英文（英语兜底）
  · 没命中规则的行（路径、pacman 原始输出等）→ 原样保留

规则按顺序匹配；{0} {1} … 引用正则捕获组。
"""
import re

# (正则, 英文模板)
RULES = [
    (r"^检查系统$", "Checking system"),
    (r"^内核: (.+)$", "Kernel: {0}"),
    (r"^架构: (.+)$", "Architecture: {0}"),
    (r"^账号: (.+)$", "Account: {0}"),
    (r"^检查驱动$", "Checking driver"),
    (r"^uvcvideo 已加载$", "uvcvideo is loaded"),
    (r"^uvcvideo 已安装（未加载）$", "uvcvideo installed (not loaded)"),
    (r"^uvcvideo 未安装，需要编译$", "uvcvideo missing — build required"),
    (r"^当前 /dev/video\* 设备数: (.+)$", "Current /dev/video* nodes: {0}"),
    (r"^/dev/video\* 数量: (.+)$", "/dev/video* nodes: {0}"),
    (r"^准备编译环境$", "Preparing build environment"),
    (r"^解锁系统只读（安装完会自动恢复）…$", "Unlocking read-only system (restored afterwards)..."),
    (r"^解锁系统只读$", "Unlocking read-only system"),
    (r"^已解锁（结束时自动恢复）$", "Unlocked (restored on exit)"),
    (r"^解锁失败（可能已经是可写状态）$", "Unlock failed (may already be writable)"),
    (r"^检查构建依赖$", "Checking build tools"),
    (r"^缺少:(.*)$", "Missing:{0}"),
    (r"^更新软件源…$", "Updating package database..."),
    (r"^软件源更新失败（继续尝试）$", "Package DB update failed (continuing)"),
    (r"^安装依赖（可能需要几分钟）…$", "Installing packages (may take a few minutes)..."),
    (r"^首次安装失败，重建密钥环后重试…$", "Install failed — rebuilding keyring and retrying..."),
    (r"^安装命令返回成功，但命令仍然缺失:(.*)$", "Install reported success but these are still missing:{0}"),
    (r"^请手动执行：(.+)$", "Please run manually: {0}"),
    (r"^构建依赖已就绪$", "Build tools ready"),
    (r"^依赖未全部装上，继续尝试编译$", "Some packages failed — continuing anyway"),
    (r"^系统没有 pacman，无法自动安装依赖$", "No pacman — cannot install packages automatically"),
    (r"^构建依赖齐全$", "Build tools already present"),
    (r"^内核头包就位$", "Kernel headers ready"),
    (r"^下载头包…$", "Downloading kernel headers..."),
    (r"^下载内核源码…$", "Downloading kernel source..."),
    (r"^展开头包（含 Valve 内核构建树）…$", "Extracting headers (Valve kernel build tree)..."),
    (r"^构建树: (.+)$", "Build tree: {0}"),
    (r"^提取 uvc 驱动源码…$", "Extracting UVC driver sources..."),
    (r"^编译驱动$", "Building driver"),
    (r"^编译 videobuf2-vmalloc \+ uvc 核心…$", "Building videobuf2-vmalloc + uvc core..."),
    (r"^编译 uvcvideo…$", "Building uvcvideo..."),
    (r"^编译完成（(.+)）$", "Build finished ({0})"),
    (r"^安装驱动$", "Installing driver"),
    (r"^depmod 完成$", "depmod done"),
    (r"^安装完成（开机自动加载）$", "Installed (loads automatically at boot)"),
    (r"^安装转发程序$", "Installing sender"),
    (r"^转发程序已安装$", "Sender installed"),
    (r"^转发程序安装失败$", "Sender install failed"),
    (r"^Bulk 相机通路已安装$", "Bulk camera path installed"),
    (r"^Bulk 通路安装失败$", "Bulk path install failed"),
    (r"^转发程序已启动（正在找 PC 接收端）$", "Sender started (looking for PC receiver)"),
    (r"^转发程序已装好，插上摄像头后会自动开始$", "Sender ready — plugging in a camera starts it automatically"),
    (r"^没拿到转发程序文件，跳过$", "Sender file missing — skipped"),
    (r"^加载驱动$", "Loading driver"),
    (r"^验证$", "Verifying"),
    (r"^检测到相机，运行自检（约 25 秒）…$", "Camera found — running self-test (~25s)..."),
    (r"^相机自检通过（能取帧，掉线可自恢复）$", "Self-test passed (frames received, recovers after unplug)"),
    (r"^相机自检未通过（若相机已插好，请拔掉数到 10 再插回）$",
     "Self-test failed (if the camera is plugged in, unplug it, count to 10, plug it back)"),
    (r"^未检测到相机（插上后会自动开始）$", "No camera detected (starts automatically once plugged in)"),
    (r"^检测到 USB 摄像头 ✓$", "USB camera detected ✓"),
    (r"^需要 root$", "Root required"),
    (r"^停止转发服务$", "Stopping sender service"),
    (r"^服务已移除$", "Service removed"),
    (r"^移除转发程序$", "Removing sender files"),
    (r"^转发程序未能删除（文件系统仍只读？）$", "Failed to delete sender files (filesystem still read-only?)"),
    (r"^转发程序已移除（环境保留）$", "Sender removed (build tools kept)"),
    (r"^卸载 UVC 驱动$", "Removing UVC driver"),
    (r"^uvcvideo 模块仍在（可能被占用）$", "uvcvideo module still present (may be in use)"),
    (r"^uvcvideo 模块已移除$", "uvcvideo module removed"),
    (r"^未发现本程序的安装痕迹，驱动保持不动$", "No trace of this app — driver left untouched"),
    (r"^回收构建环境$", "Removing build tools"),
    (r"^跳过 (.+)（共享库/系统必需）$", "Skipped {0} (shared library / required by system)"),
    (r"^已移除 (.+)$", "Removed {0}"),
    (r"^移除 (.+) 失败$", "Failed to remove {0}"),
    (r"^回收完成:(.*)$", "Cleanup done:{0}"),
    (r"^没有环境清单（旧版本装的），只清理最安全的 gcc/make$",
     "No package manifest (installed by an older version) — removing only gcc/make"),
    (r"^完成$", "Done"),
    (r"^已卸载：转发服务 \+ 转发程序 \+（若为本程序所装）uvcvideo 模块$",
     "Removed: sender service + sender files + (if installed by this app) uvcvideo module"),
    (r"^已尝试回收：构建环境（只删本程序装过且安全的包）$",
     "Build tools cleanup attempted (only packages installed by this app)"),
    (r"^已保留：构建环境、系统包、SteamOS 设置$",
     "Kept: build tools, system packages, SteamOS settings"),
]

# --- 本机（Python）日志 ---
RULES += [
    (r"^✗ 内部错误：(.*)$", "✗ Internal error: {0}"),
    (r"^正在连接 (.+) …$", "Connecting to {0} ..."),
    (r"^已连接: (.+) · 内核 (.+)$", "Connected: {0} · kernel {1}"),
    (r"^驱动状态: 已加载$", "Driver status: loaded"),
    (r"^驱动状态: 未安装$", "Driver status: not installed"),
    (r"^⚠ 密码可能不对：无法取得管理员权限$", "⚠ Wrong password? Cannot get admin rights"),
    (r"^✗ 认证失败：密码不对$", "✗ Authentication failed: wrong password"),
    (r"^✗ 连接失败: (.*)$", "✗ Connection failed: {0}"),
    (r"^连接头显$", "Connecting to headset"),
    (r"^已连接 (.+)（(.+)）$", "Connected {0} ({1})"),
    (r"^上传安装脚本$", "Uploading install script"),
    (r"^安装脚本已上传$", "Install script uploaded"),
    (r"^转发程序已上传$", "Sender uploaded"),
    (r"^⚠ 转发程序未随包携带（(.*)），将只安装驱动$",
     "⚠ Sender not bundled ({0}); installing driver only"),
    (r"^Bulk 相机通路已上传$", "Bulk camera path uploaded"),
    (r"^⚠ Bulk 通路未随包携带（(.*)）$", "⚠ Bulk path not bundled ({0})"),
    (r"^下载依赖（本机）$", "Downloading dependencies (on PC)"),
    (r"^正在下载内核头包（约 37MB）：(.*)$", "Downloading kernel headers (~37MB): {0}"),
    (r"^头包 (.+) MB$", "Headers {0} MB"),
    (r"^头包下载完成 (.+) MB ✓$", "Headers downloaded {0} MB ✓"),
    (r"^头包已缓存 ✓（(.*)）$", "Headers cached ✓ ({0})"),
    (r"^下载内核源码（本机）$", "Downloading kernel source (on PC)"),
    (r"^正在下载内核源码（约 154MB）…$", "Downloading kernel source (~154MB)..."),
    (r"^源码 (.+)%（(.+) MB）$", "Source {0}% ({1} MB)"),
    (r"^源码下载完成 (.+) MB ✓$", "Source downloaded {0} MB ✓"),
    (r"^源码已缓存 ✓$", "Source cached ✓"),
    (r"^传输到头显$", "Transferring to headset"),
    (r"^依赖已传输完毕 ✓$", "Dependencies transferred ✓"),
    (r"^安装中$", "Installing"),
    (r"^验证$", "Verifying"),
    (r"^驱动: 已加载 ✓ · /dev/video\* (\d+) 个 · USB 相机 (\d+) 个$",
     "Driver: loaded ✓ · /dev/video* {0} · USB cameras {1}"),
    (r"^驱动: 未加载 ✗ · /dev/video\* (\d+) 个 · USB 相机 (\d+) 个$",
     "Driver: not loaded ✗ · /dev/video* {0} · USB cameras {1}"),
    (r"^上传卸载脚本$", "Uploading uninstall script"),
    (r"^卸载中$", "Uninstalling"),
    (r"^（已勾选：同时回收构建环境）$", "(build tools will also be removed)"),
    (r"^✓ 已卸载（构建环境保留）$", "✓ Uninstalled (build tools kept)"),
    # 结果消息（显示在阶段标签/失败提示里）
    (r"^用户名或密码不正确（用户名默认 steamos，改过请在上面修改）$",
     "Wrong user name or password (default user: steamos)"),
    (r"^连不上头显（(.*)）：确认头显已开机并连同一 WiFi$",
     "Cannot reach headset ({0}) — make sure it is on and on the same LAN"),
    (r"^安装中断：(.*)$", "Setup aborted: {0}"),
    (r"^卸载中断：(.*)$", "Uninstall aborted: {0}"),
    (r"^驱动装上了但没加载，请重启头显后再试$",
     "Driver installed but not loaded — reboot the headset and retry"),
    (r"^安装完成$", "Setup finished"),
    (r"^卸载完成$", "Uninstall finished"),
    (r"^安装没有跑完，请看日志$", "Setup did not finish — see the log"),
    (r"^卸载没有跑完，请看日志$", "Uninstall did not finish — see the log"),
    (r"^内部错误：(.*)$", "Internal error: {0}"),
]

# --- 接收端运行日志 ---
RULES += [
    (r"^自动搜索：就绪（本机 (.*)，应答口 (\d+)（(\d+) 被占））$",
     "Auto-detect: ready (this PC {0}, reply port {1}, {2} busy)"),
    (r"^自动搜索：就绪（本机 (.*)）$", "Auto-detect: ready (this PC {0})"),
    (r"^自动搜索：就绪（(.*)）$", "Auto-detect: ready ({0})"),
    (r"^自动搜索：已应答查询（来自 (.*)），累计 (\d+) 次$",
     "Auto-detect: answered {0} ({1} total)"),
    (r"^自动搜索：不可用 —— 端口 (\d+) 已被占用(.*)$",
     "Auto-detect: unavailable — port {0} busy{1}"),
    (r"^自动搜索：启动中$", "Auto-detect: starting"),
    (r"^自动搜索不可用（端口 (\d+) 已被占用(.*)$", "Auto-detect unavailable (port {0} busy{1}"),
    (r"^VRCReceiver v(\S+) 启动 · UDP 视频口 (\d+) · MJPEG (\S+)$",
     "VRCReceiver v{0} started · UDP video port {1} · MJPEG {2}"),
    (r"^Steam Frame 选项卡不可用: (.*)$", "Steam Frame tab unavailable: {0}"),
    (r"^虚拟摄像头线程启动\.\.\.$", "Virtual camera thread started..."),
    (r"^虚拟摄像头线程退出$", "Virtual camera thread stopped"),
]

_COMPILED = [(re.compile(p), en) for p, en in RULES]

_PREFIX = re.compile(r"^(\s*)(\[(?:OK|WARN|FAIL)\]\s*|▸\s*)(.*)$")
_STAGE = re.compile(r"^(\s*=== STAGE: )(.*?)( ===\s*)$")


def _one(text: str) -> str:
    body = text.strip()
    if not body:
        return text
    for rx, en in _COMPILED:
        m = rx.match(body)
        if m:
            try:
                return en.format(*m.groups())
            except Exception:
                return body
    return body


def translate(line: str) -> str:
    """把一行中文日志翻成英文；没命中就原样返回（保留缩进与 [OK]/STAGE 标记）。"""
    if not line or not line.strip():
        return line
    m = _PREFIX.match(line)
    if m:
        return m.group(1) + m.group(2) + _one(m.group(3))
    m2 = _STAGE.match(line)
    if m2:
        return m2.group(1) + _one(m2.group(2)) + m2.group(3)
    indent = line[: len(line) - len(line.lstrip())]
    return indent + _one(line)
