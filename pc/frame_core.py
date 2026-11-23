# -*- coding: utf-8 -*-
"""frame_core.py — Steam Frame 一键安装的核心逻辑（从 frame_setup.py 抽离）

与 GUI 无关：SSH 会话、扫描、安装脚本、后台 Worker。
GUI 在 frame_tab.py，被 VRCReceiver 作为独立选项卡嵌入。
"""

from __future__ import annotations

import os
import re
import shlex
import socket
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field

from PySide6.QtCore import QObject, QThread, Qt, Signal, Slot
from PySide6.QtGui import QFont, QTextCursor
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QFrame, QHBoxLayout, QLabel,
    QLineEdit, QMessageBox, QPlainTextEdit, QProgressBar, QPushButton,
    QVBoxLayout, QWidget,
)

import paramiko

APP_VERSION = "0.3.6"
ORANGE = "#FF7A1A"
ORANGE_DIM = "#c85a10"
DARK = "#1b1b1f"
DARK2 = "#242429"
FG = "#f2f2f2"
MUTED = "#9aa0a6"

DEFAULT_USER = "steamos"

try:                      # 集成到接收端时用；独立安装器没有 i18n.py 就降级
    import i18n as _i18n
except Exception:
    _i18n = None


def _tr(line: str) -> str:
    return _i18n.log_line(line) if _i18n else line
SSH_PORT = 22


def resource_path(name: str) -> str:
    """PyInstaller 打包后从临时解包目录取资源，未打包时从脚本目录取。"""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


LOG_DIR = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "SteamFrameSetup")
LOG_FILE = os.path.join(LOG_DIR, "setup.log")
DEP_DIR = os.path.join(LOG_DIR, "deps")
SOURCE_URL = "https://mirrors.tuna.tsinghua.edu.cn/kernel/v6.x/linux-6.18.tar.xz"


def download_file(url: str, dest: str, on_progress=None, timeout: float = 30.0,
                  retries: int = 6) -> int:
    """在 PC 上下载（PC 带宽 12MB/s，头显只有 500KB/s，所以必须本机下）。

    该 CDN 时快时慢，必须带断点续传 + 重试，否则用户会看到卡死。
    """
    import urllib.request
    part = dest + ".part"
    total = 0
    for attempt in range(1, retries + 1):
        have = os.path.getsize(part) if os.path.exists(part) else 0
        headers = {"User-Agent": f"SteamFrameSetup/{APP_VERSION}"}
        if have:
            headers["Range"] = f"bytes={have}-"
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                if not total:
                    clen = int(r.headers.get("Content-Length") or 0)
                    total = clen + (have if getattr(r, "status", 200) == 206 else 0)
                with open(part, "ab" if have else "wb") as f:
                    got = have
                    while True:
                        chunk = r.read(256 * 1024)
                        if not chunk:
                            break
                        f.write(chunk)
                        got += len(chunk)
                        if on_progress:
                            on_progress(got, total)
            size = os.path.getsize(part)
            if total and size < total:
                raise IOError(f"只下到 {size}/{total}")
            os.replace(part, dest)
            return os.path.getsize(dest)
        except Exception as e:
            trace(f"下载失败(第{attempt}次): {type(e).__name__} {str(e)[:80]}")
            if attempt == retries:
                raise
            time.sleep(min(2 * attempt, 8))
    raise IOError("下载失败")


def log_to_file(msg: str):
    """所有日志同时写文件，便于出问题后直接取证（不含密码）。"""
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}\n")
    except Exception:
        pass


def trace(msg: str):
    log_to_file("[trace] " + msg)

# ============================================================================
#  Frame 上执行的安装脚本（阶段用 === STAGE: xxx === 标记，界面据此更新进度）
# ============================================================================
INSTALL_SCRIPT = r'''
set -u
say() { echo "=== STAGE: $1 ==="; }
ok()  { echo "  [OK] $*"; }
warn(){ echo "  [WARN] $*"; }
fail(){ echo "  [FAIL] $*"; }

# 无论如何退出都要恢复只读状态
trap 'steamos-readonly enable >/dev/null 2>&1 || true' EXIT

### 1. 检查系统
say "检查系统"
KVER=$(uname -r)
echo "  内核: $KVER"
echo "  架构: $(uname -m)"
echo "  账号: $(id -un) ($(id -u))"
if [ "$(id -u)" != "0" ]; then fail "没有 root 权限"; exit 10; fi
[ "$(uname -m)" = "aarch64" ] || { fail "不是 ARM64 设备"; exit 11; }

### 2. 检查是否已装
say "检查驱动"
if lsmod | grep -q '^uvcvideo'; then
  ok "uvcvideo 已加载"
  HAVE_MOD=1
elif modinfo uvcvideo >/dev/null 2>&1; then
  ok "uvcvideo 已安装（未加载）"
  HAVE_MOD=2
else
  warn "uvcvideo 未安装，需要编译"
  HAVE_MOD=0
fi
echo "  当前 /dev/video* 设备数: $(ls /dev/video* 2>/dev/null | wc -l)"

if [ "$HAVE_MOD" = "0" ]; then
  ### 3. 准备
  say "准备编译环境"
  # SteamOS 的 /usr 是只读 overlay，必须临时解锁
  echo "  解锁系统只读（安装完会自动恢复）…"
  steamos-readonly disable || { fail "无法解锁只读文件系统"; exit 19; }

  ### 3.1 补齐构建依赖（干净的头显上通常没有 gcc/make/curl 等）
  say "检查构建依赖"
  MISSING=""
  add_pkg() { case " $MISSING " in *" $1 "*) ;; *) MISSING="$MISSING $1";; esac; }
  # ★ 必须用「明确包名」逐条安装：SteamOS 里的 base-devel 是个桩包
  #   （pacman 会回 "base-devel is up to date -- nothing to do" 却不装 gcc/make）
  command -v gcc      >/dev/null 2>&1 || add_pkg gcc
  command -v make     >/dev/null 2>&1 || add_pkg make
  command -v curl     >/dev/null 2>&1 || add_pkg curl
  command -v zstd     >/dev/null 2>&1 || add_pkg zstd
  command -v depmod   >/dev/null 2>&1 || add_pkg kmod
  command -v v4l2-ctl >/dev/null 2>&1 || add_pkg v4l-utils
  command -v xz       >/dev/null 2>&1 || add_pkg xz
  command -v python3  >/dev/null 2>&1 || add_pkg python
  if [ -n "$MISSING" ]; then
    echo "  缺少:$MISSING"
    if command -v pacman >/dev/null 2>&1; then
      echo "  更新软件源…"
      pacman -Sy --noconfirm >/dev/null 2>&1 || warn "软件源更新失败（继续尝试）"
      echo "  安装依赖（可能需要几分钟）…"
      pacman -S --needed --noconfirm $MISSING > /tmp/frame_pacman.log 2>&1
      PAC=$?
      tail -10 /tmp/frame_pacman.log | sed 's/^/    /'
      if [ $PAC -ne 0 ]; then
        warn "首次安装失败，重建密钥环后重试…"
        pacman-key --init >/dev/null 2>&1 || true
        pacman-key --populate archlinux holo >/dev/null 2>&1 || true
        pacman -Sy --noconfirm >/dev/null 2>&1 || true
        pacman -S --needed --noconfirm $MISSING > /tmp/frame_pacman.log 2>&1
        PAC=$?
        tail -10 /tmp/frame_pacman.log | sed 's/^/    /'
      fi
      if [ $PAC -eq 0 ]; then
        # ★ pacman 可能"无操作"就返回 0（桩包 up to date），必须复查命令是否真的可用
        STILL=""
        for c in gcc make curl zstd depmod v4l2-ctl xz python3; do
          command -v "$c" >/dev/null 2>&1 || STILL="$STILL $c"
        done
        if [ -n "$STILL" ]; then
          warn "安装命令返回成功，但命令仍然缺失:$STILL"
          warn "请手动执行：pacman -S --needed gcc make"
        else
          ok "构建依赖已就绪"
          # 记录这次实际装了哪些包（供「同时卸载环境」精确回收）
          for p in $MISSING; do echo "$p"; done >> /etc/vrcforwarder-frame.env
          sort -u -o /etc/vrcforwarder-frame.env /etc/vrcforwarder-frame.env 2>/dev/null || true
        fi
      else
        warn "依赖未全部装上，继续尝试编译"
      fi
    else
      warn "系统没有 pacman，无法自动安装依赖"
    fi
  else
    ok "构建依赖齐全"
  fi

  WORK=/tmp/frame_uvc_build
  rm -rf "$WORK" 2>/dev/null
  mkdir -p "$WORK"; cd "$WORK"

  PC_DEPS=/home/steamos/frame_deps
  if [ -s "$PC_DEPS/headers.pkg.tar.zst" ]; then cp -f "$PC_DEPS/headers.pkg.tar.zst" .; fi
  if [ -s "$PC_DEPS/v6.18.tar.xz" ]; then cp -f "$PC_DEPS/v6.18.tar.xz" .; fi

  if [ -s headers.pkg.tar.zst ]; then
    ok "内核头包就位"
  else
    URL=$(pacman -Spdd linux-618-deckard-headers 2>/dev/null | head -1)
    [ -n "$URL" ] || { fail "找不到内核头包"; exit 20; }
    echo "  下载头包…"
    curl -L --retry 3 --max-time 900 -o headers.pkg.tar.zst "$URL" || { fail "头包下载失败"; exit 21; }
  fi

  if [ ! -s v6.18.tar.xz ]; then
    echo "  下载内核源码…"
    curl -L --retry 5 --retry-delay 3 --max-time 3600 -o v6.18.tar.xz \
      https://mirrors.tuna.tsinghua.edu.cn/kernel/v6.x/linux-6.18.tar.xz || { fail "源码下载失败"; exit 22; }
  fi

  echo "  展开头包（含 Valve 内核构建树）…"
  tar xf headers.pkg.tar.zst || { fail "头包解压失败"; exit 23; }
  KB="$WORK/usr/lib/modules/$KVER/build"
  [ -d "$KB" ] || { fail "头包里没有构建树"; exit 24; }
  ok "构建树: $KB"

  echo "  提取 uvc 驱动源码…"
  mkdir -p src
  tar xf v6.18.tar.xz -C src linux-6.18/drivers/media/usb/uvc linux-6.18/drivers/media/common \
    || { fail "源码解压失败"; exit 25; }
  SRC_COMMON="$WORK/src/linux-6.18/drivers/media/common"
  SRC_UVC="$WORK/src/linux-6.18/drivers/media/usb/uvc"
  [ -d "$SRC_UVC" ] || { fail "找不到 uvc 源码"; exit 26; }

  ### 4. 编译（外挂模块：用 Valve 的构建树，避免 struct module 尺寸不符）
  say "编译驱动"
  LOCAL=$(echo "$KVER" | sed 's/^6\.18\.0//')
  ARGS="LOCALVERSION=$LOCAL HOSTCFLAGS=-Wno-error=discarded-qualifiers CONFIG_DEBUG_INFO_BTF_MODULES="
  echo "  编译 videobuf2-vmalloc + uvc 核心…"
  make -C "$KB" M="$SRC_COMMON" CONFIG_UVC_COMMON=m CONFIG_VIDEOBUF2_VMALLOC=m $ARGS modules \
    > /tmp/uvc_build_common.log 2>&1 || { tail -15 /tmp/uvc_build_common.log; fail "编译 common 失败"; exit 27; }
  echo "  编译 uvcvideo…"
  make -C "$KB" M="$SRC_UVC" CONFIG_USB_VIDEO_CLASS=m \
    KBUILD_EXTRA_SYMBOLS="$SRC_COMMON/Module.symvers" $ARGS modules \
    > /tmp/uvc_build_uvc.log 2>&1 || { tail -15 /tmp/uvc_build_uvc.log; fail "编译 uvcvideo 失败"; exit 28; }

  VB2="$SRC_COMMON/videobuf2/videobuf2-vmalloc.ko"
  UVCC="$SRC_COMMON/uvc.ko"
  UVCV="$SRC_UVC/uvcvideo.ko"
  for f in "$VB2" "$UVCC" "$UVCV"; do
    [ -s "$f" ] || { fail "缺少产物 $f"; exit 29; }
  done
  ok "编译完成（$(basename "$UVCV") $(stat -c %s "$UVCV") 字节）"

  ### 5. 安装
  say "安装驱动"
  insmod "$VB2" 2>/dev/null
  insmod "$UVCC" 2>/dev/null
  if ! insmod "$UVCV" 2>&1; then
    echo "  --- dmesg ---"; dmesg | tail -5
    fail "uvcvideo 加载失败"
  fi
  MOD_DIR="/lib/modules/$KVER/kernel/drivers/media"
  mkdir -p "$MOD_DIR/common/videobuf2" "$MOD_DIR/usb/uvc"
  cp -f "$VB2" "$MOD_DIR/common/videobuf2/"
  cp -f "$UVCC" "$MOD_DIR/common/"
  cp -f "$UVCV" "$MOD_DIR/usb/uvc/"
  depmod -a && ok "depmod 完成"
  echo 'uvcvideo' > /etc/modules-load.d/90-uvcvideo.conf
  mkdir -p /etc/atomic-update.conf.d
  echo '/etc/modules-load.d/90-uvcvideo.conf' > /etc/atomic-update.conf.d/90-uvcvideo-modules-load.d.conf
  # 标记：驱动是我们装的（卸载时据此决定是否删除模块，避免误删系统自带）
  echo "$KVER" > /etc/vrcforwarder-frame.installed
  ok "安装完成（开机自动加载）"
fi

### 6. 安装转发程序
say "安装转发程序"
if [ -f /tmp/frame_sender.py ]; then
  steamos-readonly disable >/dev/null 2>&1 || true
  install -m 755 -o root -g root /tmp/frame_sender.py /usr/local/bin/frame_sender.py \
    && ok "转发程序已安装" || warn "转发程序安装失败"
  if [ -f /tmp/uvc_bulk_sender.py ]; then
    install -m 755 -o root -g root /tmp/uvc_bulk_sender.py /usr/local/bin/uvc_bulk_sender.py \
      && ok "Bulk 相机通路已安装" || warn "Bulk 通路安装失败"
  fi
  cat > /etc/systemd/system/frame-sender.service <<'UNIT'
[Unit]
Description=Steam Frame Camera Sender (VRCForwarder)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=steamos
SupplementaryGroups=video input
ExecStart=/usr/bin/python3 /usr/local/bin/frame_sender.py --device auto --backend auto --stream auto
Restart=always
RestartSec=1
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload
  systemctl enable frame-sender.service >/dev/null 2>&1
  systemctl restart frame-sender.service >/dev/null 2>&1 || true
  sleep 2
  if systemctl is-active frame-sender.service >/dev/null 2>&1; then
    ok "转发程序已启动（正在找 PC 接收端）"
  else
    warn "转发程序已装好，插上摄像头后会自动开始"
  fi
else
  warn "没拿到转发程序文件，跳过"
fi

### 7. 加载并验证
say "加载驱动"
if ! lsmod | grep -q '^uvcvideo'; then
  modprobe uvcvideo 2>/dev/null || { dmesg | tail -8; fail "modprobe 失败"; }
fi
sleep 1
lsmod | grep -E '^(uvcvideo|uvc|videobuf2_vmalloc)' | sed 's/^/    /'
modinfo uvcvideo 2>/dev/null | grep -E '^(filename|vermagic)' | sed 's/^/    /'

say "验证"
echo "  /dev/video* 数量: $(ls /dev/video* 2>/dev/null | wc -l)"
# 相机自检：真正取帧 + 主动复位 + 再取帧，把结果直接报给安装器
if [ -f /usr/local/bin/frame_sender.py ] && [ -f /usr/local/bin/uvc_bulk_sender.py ]; then
  if lsusb 2>/dev/null | grep -qiE '303a:8000|303a:'; then
    echo "  检测到相机，运行自检（约 25 秒）…"
    systemctl stop frame-sender.service 2>/dev/null
    sleep 1
    OUT=$(su - steamos -c "/usr/bin/python3 /usr/local/bin/frame_sender.py --selftest" 2>&1 || true)
    echo "$OUT" | grep -E "自检|取到|SELFTEST" | tail -8 | sed 's/^/    /'
    systemctl start frame-sender.service 2>/dev/null
    if echo "$OUT" | grep -q "SELFTEST: PASS"; then
      ok "相机自检通过（能取帧，掉线可自恢复）"
    else
      warn "相机自检未通过（若相机已插好，请拔掉数到 10 再插回）"
    fi
  else
    warn "未检测到相机（插上后会自动开始）"
  fi
fi
if lsusb 2>/dev/null | grep -qiE 'camera|webcam|uvc'; then
  echo "  检测到 USB 摄像头 ✓"
fi
echo "=== STAGE: DONE ==="
echo "ALL_OK"
'''

UNINSTALL_SCRIPT = r'''#!/bin/bash
# Steam Frame：只卸载 VRCForwarder 的相机转发功能与自装的 uvcvideo 模块。
# ⚠ 不动构建环境（gcc/make/curl/头包等），也不动 SteamOS 系统包。
set +e
say() { echo "=== STAGE: $* ==="; }
ok()   { echo "  [OK] $*"; }
warn() { echo "  [WARN] $*"; }

KVER=$(uname -r)
[ "$(id -u)" = "0" ] || { echo "需要 root"; exit 10; }
trap 'steamos-readonly enable >/dev/null 2>&1 || true' EXIT

# ★ /usr 是只读 overlay：必须一开始就解锁，否则删 /usr/local/bin 会报
#   "Read-only file system"。结束时由 trap 自动恢复只读。
say "解锁系统只读"
if steamos-readonly disable >/dev/null 2>&1; then
  ok "已解锁（结束时自动恢复）"
else
  warn "解锁失败（可能已经是可写状态）"
fi

say "停止转发服务"
systemctl stop frame-sender.service 2>/dev/null
systemctl disable frame-sender.service 2>/dev/null
rm -f /etc/systemd/system/frame-sender.service
systemctl daemon-reload 2>/dev/null
ok "服务已移除"

say "移除转发程序"
rm -f /usr/local/bin/frame_sender.py /usr/local/bin/uvc_bulk_sender.py 2>/dev/null
if [ -e /usr/local/bin/frame_sender.py ] || [ -e /usr/local/bin/uvc_bulk_sender.py ]; then
  warn "转发程序未能删除（文件系统仍只读？）"
else
  ok "转发程序已移除（环境保留）"
fi
rm -rf /tmp/frame_uvc_build /tmp/frame_sender.lost 2>/dev/null

say "卸载 UVC 驱动"
# 判定「是我们装的」：新版本写标记；老版本没标记但有下面这个 modules-load 文件
if [ -f /etc/vrcforwarder-frame.installed ] || [ -f /etc/modules-load.d/90-uvcvideo.conf ]; then
  rmmod uvcvideo 2>/dev/null; rmmod uvc 2>/dev/null; rmmod videobuf2_vmalloc 2>/dev/null
  rm -f /etc/modules-load.d/90-uvcvideo.conf
  rm -f /etc/atomic-update.conf.d/90-uvcvideo-modules-load.d.conf
  MOD_DIR="/lib/modules/$KVER/kernel/drivers/media"
  rm -f "$MOD_DIR/usb/uvc/uvcvideo.ko" "$MOD_DIR/usb/uvc/uvcvideo.ko.zst"
  rm -f "$MOD_DIR/common/uvc.ko" "$MOD_DIR/common/uvc.ko.zst"
  rm -f "$MOD_DIR/common/videobuf2/videobuf2-vmalloc.ko" \
        "$MOD_DIR/common/videobuf2/videobuf2-vmalloc.ko.zst"
  depmod -a 2>/dev/null
  rm -f /etc/vrcforwarder-frame.installed
  if modinfo -n uvcvideo >/dev/null 2>&1; then
    warn "uvcvideo 模块仍在（可能被占用）"
  else
    ok "uvcvideo 模块已移除"
  fi
else
  warn "未发现本程序的安装痕迹，驱动保持不动"
fi

if [ "$PURGE_ENV" = "1" ]; then
  say "回收构建环境"
  # 绝不删这些：同时提供共享库或被系统依赖，删了会影响 SteamOS / Steam
  NEVER="curl zstd kmod python python2 xz gcc-libs glibc base-devel libarchive"
  PURGED=""
  if [ -f /etc/vrcforwarder-frame.env ]; then
    for p in $(cat /etc/vrcforwarder-frame.env); do
      case " $NEVER " in
        *" $p "*) echo "  跳过 $p（共享库/系统必需）"; continue ;;
      esac
      if pacman -Q "$p" >/dev/null 2>&1; then
        if pacman -Rdd --noconfirm "$p" >/tmp/purge_$p.log 2>&1; then
          echo "  已移除 $p"
          PURGED="$PURGED $p"
        else
          echo "  移除 $p 失败"; tail -3 /tmp/purge_$p.log | sed 's/^/    /'
        fi
      fi
    done
    rm -f /etc/vrcforwarder-frame.env
    echo "  回收完成:$PURGED"
  else
    warn "没有环境清单（旧版本装的），只清理最安全的 gcc/make"
    for p in gcc make; do
      if pacman -Q "$p" >/dev/null 2>&1; then
        pacman -Rdd --noconfirm "$p" >/tmp/purge_$p.log 2>&1 \
          && echo "  已移除 $p" || echo "  移除 $p 失败"
      fi
    done
  fi
fi

say "完成"
echo "  已卸载：转发服务 + 转发程序 +（若为本程序所装）uvcvideo 模块"
if [ "$PURGE_ENV" = "1" ]; then
  echo "  已尝试回收：构建环境（只删本程序装过且安全的包）"
else
  echo "  已保留：构建环境、系统包、SteamOS 设置"
fi
echo "=== STAGE: DONE ==="
echo "ALL_OK"
'''


# ============================================================================
#  SSH 会话
# ============================================================================
class FrameSession:
    """一次 SSH 连接 + sudo 执行能力。密码只存在内存里，不落盘。"""

    def __init__(self, host: str, user: str = DEFAULT_USER, password: str | None = None):
        self.host = host
        self.user = user
        self._password = password
        self.client: paramiko.SSHClient | None = None

    # -- 连接 --------------------------------------------------------------
    def connect(self, timeout: float = 12.0) -> str:
        trace(f"connect 开始 {self.user}@{self.host} 认证={'密码' if self._password else '本机密钥'}")
        c = paramiko.SSHClient()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        kwargs = dict(username=self.user, timeout=timeout,
                      banner_timeout=timeout, auth_timeout=timeout,
                      look_for_keys=False, allow_agent=False)
        if self._password:
            kwargs["password"] = self._password
        else:
            # 没给密码时尝试本机已有私钥
            kwargs["look_for_keys"] = True
        c.connect(self.host, **kwargs)
        self.client = c
        trace("connect 完成")
        return "connected"

    def close(self):
        try:
            if self.client:
                self.client.close()
        except Exception:
            pass
        self.client = None

    # -- 执行 --------------------------------------------------------------
    def run(self, cmd: str, timeout: float = 60.0) -> tuple[int, str]:
        """执行命令并带硬超时（不再依赖 read() 的隐式阻塞）。"""
        assert self.client, "未连接"
        t0 = time.time()
        trace(f"run 开始: {cmd[:60]}")
        ch = self.client.get_transport().open_session()
        ch.settimeout(timeout)
        ch.exec_command(cmd)
        out = b""
        while True:
            if ch.recv_ready():
                data = ch.recv(65536)
                if not data:
                    break
                out += data
            elif ch.exit_status_ready():
                while ch.recv_ready():
                    out += ch.recv(65536)
                break
            else:
                time.sleep(0.03)
                if time.time() - t0 > timeout:
                    ch.close()
                    raise TimeoutError(f"命令超时 {timeout:.0f}s: {cmd[:40]}")
        rc = ch.recv_exit_status()
        ch.close()
        text = out.decode("utf-8", "replace").strip()
        trace(f"run 结束({time.time()-t0:.1f}s) rc={rc}: {cmd[:40]}")
        return rc, text

    def run_sudo(self, cmd: str, timeout: float = 5400.0, on_line=None,
                 idle_timeout: float = 60.0) -> tuple[int, str]:
        """流式执行 sudo 命令。

        - on_line: 每收到一行就回调（界面实时显示进度）
        - 密码错误/无响应时**快速失败**，不再干等到超时
        """
        assert self.client and self._password, "缺密码"
        transport = self.client.get_transport()
        ch = transport.open_session()
        ch.get_pty()
        ch.settimeout(0.5)
        ch.exec_command(f"sudo -S -p '' bash -lc {shlex.quote(cmd)}")
        ch.sendall((self._password + "\n").encode())

        lines: list[str] = []
        buf = b""
        t0 = time.time()
        t_last = time.time()
        wrong_pw = False
        secret = self._password or ""

        def _emit(line: str):
            """统一出口：绝不把密码回显写进日志/界面。"""
            if secret and line.strip() == secret:
                return
            lines.append(line)
            if on_line:
                on_line(line)

        try:
            while True:
                if ch.recv_ready():
                    data = ch.recv(65536)
                    if not data:
                        break
                    buf += data
                    t_last = time.time()
                    while b"\n" in buf:
                        raw, buf = buf.split(b"\n", 1)
                        line = raw.decode("utf-8", "replace").strip()
                        if re.search(r"Sorry, try again|incorrect password attempt|not in the sudoers|no tty present",
                                     line, re.I):
                            wrong_pw = True
                        _emit(line)
                elif ch.exit_status_ready():
                    # 收尾：把缓冲里剩下的读干净
                    while ch.recv_ready():
                        data = ch.recv(65536)
                        if not data:
                            break
                        buf += data
                    break
                else:
                    time.sleep(0.05)
                    if int(time.time() - t_last) > 0 and int(time.time() - t_last) % 10 == 0:
                        if not getattr(self, "_hb", None) or time.time() - self._hb >= 10:
                            self._hb = time.time()
                            trace(f"run_sudo 等待输出中，已 {time.time()-t0:.0f}s（无新数据）")
                            if on_line:
                                on_line(f"  …等待输出（已 {time.time()-t0:.0f}s）")

                if wrong_pw:
                    break
                if idle_timeout and (time.time() - t_last) > idle_timeout:
                    lines.append("  [FAIL] 长时间无响应（可能密码不对、网络断开或磁盘写满）")
                    if on_line:
                        on_line(lines[-1])
                    break
                if timeout and (time.time() - t0) > timeout:
                    lines.append("  [FAIL] 总时长超限，已中止")
                    if on_line:
                        on_line(lines[-1])
                    break
        finally:
            try:
                ch.close()
            except Exception:
                pass

        if buf:
            tail = buf.decode("utf-8", "replace").strip()
            if tail:
                _emit(tail)
        if wrong_pw:
            lines.append("  [FAIL] 密码不正确（管理员权限验证失败）")
        return (1 if wrong_pw else 0), "\n".join(lines)

    # -- 逐个探测 ----------------------------------------------------------
    def probe(self) -> dict:
        trace("probe 开始")
        info = {}
        rc, out = self.run("hostname; uname -r; uname -m; id -un")
        lines = [x.strip() for x in out.splitlines() if x.strip()]
        if len(lines) >= 4:
            info["hostname"], info["kernel"], info["arch"], info["user"] = (
                lines[0], lines[1], lines[2], lines[3])
        rc, out = self.run("lsmod | grep -c '^uvcvideo' || true")
        info["uvc_loaded"] = out.strip().isdigit() and int(out.strip()) > 0
        rc, out = self.run("ls /dev/video* 2>/dev/null | wc -l")
        info["video_count"] = int(out.strip() or 0)
        rc, out = self.run("lsusb 2>/dev/null | grep -ciE 'camera|webcam' || true")
        info["usb_cameras"] = int(out.strip() or 0)
        trace("probe: 开始验证 sudo 权限")
        info["sudo_ok"] = self._check_sudo()
        trace(f"probe 完成 sudo_ok={info['sudo_ok']}")
        return info

    def _check_sudo(self) -> bool:
        if not self._password:
            rc, out = self.run("sudo -n true 2>&1 && echo SUDO_OK")
            return "SUDO_OK" in out
        rc, out = self.run_sudo("true", timeout=30, idle_timeout=15)
        return rc == 0 and "[FAIL]" not in out


# ============================================================================
#  设备发现
# ============================================================================
def local_ipv4() -> str | None:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return None


def _port_open(ip: str, port: int = SSH_PORT, timeout: float = 0.4) -> bool:
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except Exception:
        return False


def scan_lan(cb=None) -> list[str]:
    """扫本机所在 /24，返回开了 22 端口的主机。"""
    me = local_ipv4()
    if not me:
        return []
    base = me.rsplit(".", 1)[0]
    hosts = [f"{base}.{i}" for i in range(1, 255)] + ["frame.local"]
    found = []
    with ThreadPoolExecutor(max_workers=64) as ex:
        futs = {ex.submit(_port_open, h): h for h in hosts}
        for f in as_completed(futs):
            if f.result():
                found.append(futs[f])
                if cb:
                    cb(f"  发现主机: {futs[f]}")
    return found


# ============================================================================
#  后台工作线程
# ============================================================================
@dataclass
class InstallResult:
    ok: bool = False
    message: str = ""
    log: str = ""


class Worker(QObject):
    log = Signal(str)
    stage = Signal(str)
    progress = Signal(int)
    finished = Signal(object)
    device = Signal(dict)

    def __init__(self):
        super().__init__()
        self._session: FrameSession | None = None
        self._fn = None
        self._args: tuple = ()
        self._kw: dict = {}

    # -- 线程入口（必须是 @Slot，才能被投递到本对象所在线程执行）----------
    def prepare(self, fn, *args, **kwargs):
        """在 GUI 线程调用：把要执行的任务存起来。"""
        self._fn = fn
        self._args = args
        self._kw = kwargs

    @Slot()
    def run(self):
        """由 QThread.started 触发，运行在后台线程。"""
        try:
            if self._fn:
                self._fn(self, *self._args, **self._kw)
        except Exception as e:
            import traceback
            trace(f"worker 异常: {e}")
            try:
                self.log.emit(_tr(f"✗ 内部错误：{type(e).__name__}: {e}"))
                self.finished.emit(InstallResult(False, f"内部错误：{e}"))
            except Exception:
                pass

    # -- 发现 + 连接 -------------------------------------------------------
    def do_connect(self, host: str, user: str, password: str):
        try:
            self.log.emit(_tr(f"正在连接 {host} …"))
            s = FrameSession(host, user, password)
            s.connect()
            info = s.probe()
            self._session = s
            self.device.emit(info)
            self.log.emit(_tr(f"已连接: {info.get('hostname','?')} · 内核 {info.get('kernel','?')}"))
            self.log.emit(_tr(f"驱动状态: {'已加载' if info.get('uvc_loaded') else '未安装'}"
                          f" · 摄像头设备数 {info.get('video_count')}"))
            if not info.get("sudo_ok"):
                self.log.emit(_tr("⚠ 密码可能不对：无法取得管理员权限"))
                self.finished.emit(InstallResult(False, "密码验证失败，请检查密码"))
            else:
                self.finished.emit(InstallResult(True, "连接成功"))
        except paramiko.AuthenticationException:
            self.log.emit(_tr("✗ 认证失败：密码不对"))
            self.finished.emit(InstallResult(False, "密码不正确"))
        except Exception as e:
            self.log.emit(_tr(f"✗ 连接失败: {e}"))
            self.finished.emit(InstallResult(False, f"连不上 {host}：{e}"))

    # -- 一键安装 ----------------------------------------------------------
    def do_install(self, host: str, user: str, password: str):
        ok = True
        msg = "安装完成"
        logbuf = []
        try:
            self.stage.emit(_tr("连接头显"))
            self.progress.emit(3)
            s = FrameSession(host, user, password)
            s.connect()
            self._session = s
            info = s.probe()
            self.device.emit(info)
            self.log.emit(_tr(f"已连接 {info.get('hostname','?')}（{info.get('kernel','?')}）"))
            if not info.get("sudo_ok"):
                raise RuntimeError("密码验证失败（拿不到管理员权限）")

            self.stage.emit(_tr("上传安装脚本"))
            self.progress.emit(8)
            self._sftp_put(INSTALL_SCRIPT, "/tmp/frame_uvc_install.sh")
            self.log.emit(_tr("安装脚本已上传"))
            # 转发程序（随包携带）
            try:
                with open(resource_path("frame_sender.py"), "r", encoding="utf-8") as f:
                    sender_src = f.read()
                self._sftp_put(sender_src, "/tmp/frame_sender.py")
                self.log.emit(_tr("转发程序已上传"))
            except Exception as e:
                self.log.emit(_tr(f"⚠ 转发程序未随包携带（{e}），将只安装驱动"))
            # Bulk 相机直读通路（XIAO 这类）
            try:
                with open(resource_path("uvc_bulk_sender.py"), "r", encoding="utf-8") as f:
                    bulk_src = f.read()
                self._sftp_put(bulk_src, "/tmp/uvc_bulk_sender.py")
                self.log.emit(_tr("Bulk 相机通路已上传"))
            except Exception as e:
                self.log.emit(_tr(f"⚠ Bulk 通路未随包携带（{e}）"))

            # ---- 在 PC 上下载依赖，再传过去（头显直连下载只有 ~500KB/s）----
            os.makedirs(DEP_DIR, exist_ok=True)
            self.stage.emit(_tr("下载依赖（本机）"))
            self.progress.emit(15)

            hd_path = None
            rc, url = s.run("pacman -Spdd linux-618-deckard-headers 2>/dev/null | head -1", timeout=30)
            url = (url or "").strip().splitlines()[-1].strip() if url else ""
            if not url.startswith("http"):
                raise RuntimeError("取不到内核头包下载地址")
            # 用 URL 里的文件名做缓存名：系统更新后内核变了，文件名也会变，自动重新下载
            hd_name = os.path.basename(url.split("?")[0]) or "headers.pkg.tar.zst"
            hd_path = os.path.join(DEP_DIR, hd_name)
            if not (os.path.exists(hd_path) and os.path.getsize(hd_path) > 30_000_000):
                self.log.emit(_tr(f"正在下载内核头包（约 37MB）：{hd_name}"))

                last = {"mb": 0}

                def hd_cb(got, total):
                    mb = int(got / 1024 / 1024)
                    if mb >= last["mb"] + 5:
                        last["mb"] = mb
                        self.log.emit(_tr(f"  头包 {mb} MB" + (f" / {total//1024//1024} MB" if total else "")))
                download_file(url, hd_path, hd_cb, timeout=120)
                self.log.emit(_tr(f"头包下载完成 {os.path.getsize(hd_path)//1024//1024} MB ✓"))
            else:
                self.log.emit(_tr(f"头包已缓存 ✓（{hd_name}）"))

            src_path = os.path.join(DEP_DIR, "v6.18.tar.xz")
            if not (os.path.exists(src_path) and os.path.getsize(src_path) > 100_000_000):
                self.stage.emit(_tr("下载内核源码（本机）"))
                self.progress.emit(28)
                self.log.emit(_tr("正在下载内核源码（约 154MB）…"))

                last = {"pct": -10}

                def src_cb(got, total):
                    pct = int(got * 100 / total) if total else 0
                    if pct >= last["pct"] + 10:
                        last["pct"] = pct
                        self.log.emit(_tr(f"  源码 {pct}%（{got//1024//1024} MB）"))
                download_file(SOURCE_URL, src_path, src_cb, timeout=120)
                self.log.emit(_tr(f"源码下载完成 {os.path.getsize(src_path)//1024//1024} MB ✓"))
            else:
                self.log.emit(_tr("源码已缓存 ✓"))

            self.stage.emit(_tr("传输到头显"))
            self.progress.emit(42)
            s.run("mkdir -p /home/steamos/frame_deps", timeout=20)
            # 远端文件名固定为脚本期望的名字
            self._sftp_put_file(hd_path, "/home/steamos/frame_deps/headers.pkg.tar.zst",
                                lambda d, t: self._upload_tick("头包", d, t))
            self._sftp_put_file(src_path, "/home/steamos/frame_deps/v6.18.tar.xz",
                                lambda d, t: self._upload_tick("源码", d, t))
            self.log.emit(_tr("依赖已传输完毕 ✓"))

            self.stage.emit(_tr("安装中"))
            self.progress.emit(55)
            stage_re = re.compile(r"^=== STAGE: (.+) ===$")
            state = {"p": 55, "ok": True, "msg": ""}

            def on_line(line: str):
                line = _tr(line.rstrip())
                m = stage_re.match(line.strip())
                if m:
                    name = m.group(1)
                    if name == "DONE":
                        self.progress.emit(95)
                        return
                    self.stage.emit(_tr(name))
                    state["p"] = min(90, state["p"] + 12)
                    self.progress.emit(state["p"])
                    self.log.emit(_tr(f"▸ {name}"))
                    return
                if not line.strip():
                    return
                self.log.emit(_tr(line))
                if "[FAIL]" in line:
                    state["ok"] = False
                    state["msg"] = line.split("[FAIL]", 1)[1].strip()

            rc, out = s.run_sudo("bash /tmp/frame_uvc_install.sh",
                                 timeout=5400, on_line=on_line, idle_timeout=150)
            ok = state["ok"] and rc == 0 and "ALL_OK" in out
            if not ok:
                msg = state["msg"] or ("密码不正确" if "密码不正确" in out else "安装没有跑完，请看日志")

            self.stage.emit(_tr("验证"))
            self.progress.emit(96)
            info2 = s.probe()
            self.device.emit(info2)
            self.log.emit(_tr(f"驱动: {'已加载 ✓' if info2.get('uvc_loaded') else '未加载 ✗'}"
                          f" · /dev/video* {info2.get('video_count')} 个"
                          f" · USB 相机 {info2.get('usb_cameras')} 个"))
            if ok and not info2.get("uvc_loaded"):
                ok = False
                msg = "驱动装上了但没加载，请重启头显后再试"
            self.progress.emit(100)
        except Exception as e:
            ok = False
            es = str(e)
            if "Authentication failed" in es or "authentication" in es.lower():
                msg = "用户名或密码不正确（用户名默认 steamos，改过请在上面修改）"
            elif "Unable to connect" in es or "timed out" in es:
                msg = f"连不上头显（{es}）：确认头显已开机并连同一 WiFi"
            else:
                msg = f"安装中断：{es}"
            self.log.emit(_tr(f"✗ {msg}"))
        finally:
            try:
                if self._session:
                    self._session.close()
            except Exception:
                pass
            self.finished.emit(InstallResult(ok, msg, "\n".join(logbuf)))

    # -- 卸载（只卸 uvcvideo + 转发，构建环境保留）-------------------------
    def do_uninstall(self, host: str, user: str, password: str, purge_env: bool = False):
        ok = True
        msg = "卸载完成"
        try:
            self.stage.emit(_tr("连接头显"))
            self.progress.emit(5)
            s = FrameSession(host, user, password)
            s.connect()
            self._session = s
            info = s.probe()
            self.device.emit(info)
            self.log.emit(_tr(f"已连接 {info.get('hostname','?')}（{info.get('kernel','?')}）"))

            self.stage.emit(_tr("上传卸载脚本"))
            self.progress.emit(25)
            self._sftp_put(UNINSTALL_SCRIPT, "/tmp/frame_uvc_uninstall.sh")

            self.stage.emit(_tr("卸载中"))
            self.progress.emit(45)
            stage_re = re.compile(r"^=== STAGE: (.+) ===$")
            state = {"p": 45, "ok": True, "msg": ""}

            def on_line(line: str):
                line = _tr(line.rstrip())
                m = stage_re.match(line.strip())
                if m:
                    name = m.group(1)
                    if name == "DONE":
                        self.progress.emit(95)
                        return
                    self.stage.emit(_tr(name))
                    state["p"] = min(90, state["p"] + 15)
                    self.progress.emit(state["p"])
                    self.log.emit(_tr(f"▸ {name}"))
                    return
                if line.strip():
                    self.log.emit(_tr(line))
                    if "[FAIL]" in line:
                        state["ok"] = False
                        state["msg"] = line.split("[FAIL]", 1)[1].strip()

            cmd = "bash /tmp/frame_uvc_uninstall.sh"
            if purge_env:
                cmd = "PURGE_ENV=1 " + cmd
                self.log.emit(_tr("（已勾选：同时回收构建环境）"))
            rc, out = s.run_sudo(cmd, timeout=900, on_line=on_line, idle_timeout=90)
            ok = state["ok"] and rc == 0 and "ALL_OK" in out
            if not ok:
                msg = state["msg"] or "卸载没有跑完，请看日志"
            else:
                self.progress.emit(100)
                self.log.emit(_tr("✓ 已卸载（构建环境保留）"))
        except Exception as e:
            ok = False
            es = str(e)
            if "Authentication failed" in es or "authentication" in es.lower():
                msg = "用户名或密码不正确（用户名默认 steamos，改过请在上面修改）"
            elif "Unable to connect" in es or "timed out" in es:
                msg = f"连不上头显（{es}）：确认头显已开机并连同一 WiFi"
            else:
                msg = f"卸载中断：{es}"
            self.log.emit(_tr(f"✗ {msg}"))
        finally:
            try:
                if self._session:
                    self._session.close()
            except Exception:
                pass
            self.finished.emit(InstallResult(ok, msg, ""))

    def _sftp_put(self, text: str, remote: str):
        assert self._session and self._session.client
        trace(f"sftp 上传开始 -> {remote}")
        sftp = self._session.client.open_sftp()
        sftp.get_channel().settimeout(60)
        with sftp.file(remote, "w") as f:
            f.write(text.replace("\r\n", "\n").replace("\r", "\n"))
        sftp.close()
        trace(f"sftp 上传完成 {remote}")

    def _sftp_put_file(self, local: str, remote: str, cb=None):
        assert self._session and self._session.client
        size = os.path.getsize(local)
        trace(f"sftp 传输文件 {os.path.basename(local)} ({size//1024//1024}MB) -> {remote}")
        sftp = self._session.client.open_sftp()
        sftp.get_channel().settimeout(1800)
        sftp.put(local, remote, callback=cb)
        sftp.close()
        trace(f"sftp 传输完成 {remote}")

    def _upload_tick(self, name: str, done: int, total: int):
        pct = int(done * 100 / total) if total else 0
        key = f"_up_{name}"
        last = getattr(self, key, -10)
        if pct >= last + 10:
            setattr(self, key, pct)
            self.log.emit(_tr(f"  {name} {pct}%（{done//1024//1024}/{total//1024//1024} MB）"))

