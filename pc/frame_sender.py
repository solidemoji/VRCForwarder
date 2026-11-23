#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Steam Frame 端发送端
V4L2 摄像头 (MJPEG) --ffmpeg--> 本脚本分包 --UDP--> PC 接收端 (VRCReceiver)

协议 v2（与 Android 端、PC 端完全一致）:
    magic 'UV'(2) + stream_id(1) + seq(u32 LE) + fidx(u16 LE) + fcnt(u16 LE) = 11B
    单片负载 <= 1200 字节

用法:
    frame_sender.py                      # 自动搜索 PC 并发送
    frame_sender.py --to 192.168.1.5:5000 --device /dev/video0
    frame_sender.py --device auto --size 640x480 --fps 30 --stream 0
"""
from __future__ import annotations

import argparse
import copy
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time

MAGIC = b"UV"
CFG_MAGIC = b"UC"          # 接收端 → 发送端 的规格下发包
RC_LOST = 3                 # 设备掉线/被拔
RC_RESTART = 5              # 规格变更，需要按新规格重启这一路
HEADER_LEN = 11
PAYLOAD_MAX = 1200
BEACON_PORT_CANDIDATES = [5001] + list(range(5011, 5020))
DISCOVER_MSG = b"UVC-FORWARDER-DISCOVER"
REPLY_PREFIX = b"UVC-RECEIVER "

SOI = b"\xff\xd8"   # JPEG 起始
EOI = b"\xff\xd9"   # JPEG 结束


def log(msg: str):
    print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


# ---------------------------------------------------------------------------
#  发现 PC 接收端
# ---------------------------------------------------------------------------
def local_ip() -> str | None:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return None


def discover(timeout: float = 6.0) -> tuple[str, int] | None:
    """广播找 PC 接收端，返回 (ip, port)。"""
    ip = local_ip()
    if not ip:
        return None
    subnet = ".".join(ip.split(".")[:3]) + ".255"
    deadline = time.time() + timeout
    socks = []
    for bp in BEACON_PORT_CANDIDATES:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("", 0))
        except Exception:
            s.close()
            continue
        s.settimeout(0.4)
        socks.append((bp, s))

    for bp, s in socks:
        for target in ("255.255.255.255", subnet):
            try:
                s.sendto(DISCOVER_MSG, (target, bp))
            except Exception:
                pass
    log(f"已广播查找接收端（本机 {ip}）")

    while time.time() < deadline:
        for bp, s in socks:
            try:
                data, addr = s.recvfrom(512)
            except socket.timeout:
                continue
            except Exception:
                continue
            if not data.startswith(REPLY_PREFIX):
                continue
            body = data[len(REPLY_PREFIX):].decode("utf-8", "replace").strip()
            host, _, port = body.partition(":")
            port = int(port) if port.isdigit() else 5000
            for _, ss in socks:
                ss.close()
            log(f"找到接收端: {host}:{port}")
            return host, port
    for _, s in socks:
        s.close()
    return None


# ---------------------------------------------------------------------------
#  摄像头
# ---------------------------------------------------------------------------
def list_video_devices() -> list[tuple[str, str]]:
    """只列出真正的取流节点（/dev/videoN），并带上驱动名。"""
    out = []
    for d in sorted(os.listdir("/sys/class/video4linux")):
        if not re.fullmatch(r"video\d+", d):        # 排除 v4l-subdev*
            continue
        path = f"/dev/{d}"
        try:
            with open(f"/sys/class/video4linux/{d}/name") as f:
                name = f.read().strip()
        except Exception:
            continue
        driver = ""
        try:
            driver = os.path.basename(
                os.path.realpath(f"/sys/class/video4linux/{d}/device/driver"))
        except Exception:
            pass
        out.append((path, f"{name}|{driver}"))
    return out


def probe_caps(dev: str) -> str:
    """返回该节点支持的格式概要（用于判断能不能取流）。"""
    try:
        r = subprocess.run(["v4l2-ctl", "-d", dev, "--list-formats-ext"],
                           capture_output=True, text=True, timeout=10)
        return r.stdout
    except Exception:
        return ""


def parse_all_formats(caps: str, limit: int = 40):
    """解析设备支持的全部规格组合：[{fmt,w,h,fps}, ...]（真机是离散组合，不能自由拼）。"""
    out, seen = [], set()
    fmt = None
    size = None
    for line in caps.splitlines():
        m = re.search(r"'(\w{4})'", line)
        if m:
            fmt = m.group(1)
            continue
        m = re.search(r"Size:\s*Discrete\s+(\d+)x(\d+)", line)
        if m:
            size = (int(m.group(1)), int(m.group(2)))
            continue
        m = re.search(r"\(([\d.]+)(?:-[\d.]+)?\s*fps\)", line)
        if m and fmt and size:
            fps = int(float(m.group(1)))
            if 0 < fps <= 240:
                key = (fmt, size[0], size[1], fps)
                if key not in seen:
                    seen.add(key)
                    out.append({"fmt": fmt, "w": size[0], "h": size[1], "fps": fps})
                    if len(out) >= limit:
                        return out
    return out


def parse_first_format(caps: str):
    """从 v4l2-ctl 输出里解析第一个可用组合 → (fourcc, 'WxH', fps)。"""
    fmt = None
    size = None
    fps = None
    for line in caps.splitlines():
        m = re.search(r"'(\w{4})'", line)
        if m and not fmt:
            fmt = m.group(1)
            continue
        m = re.search(r"Size:\s*Discrete\s+(\d+x\d+)", line)
        if m and fmt and not size:
            size = m.group(1)
            continue
        m = re.search(r"\(([\d.]+)(?:-[\d.]+)?\s*fps\)", line)
        if m and size and not fps:
            fps = int(float(m.group(1)))
            break
    return fmt, size, fps


def pick_camera(explicit: str | None) -> str | None:
    """优先选 uvcvideo 驱动、真实 video 节点、且确实能枚举出格式的设备。"""
    if explicit and explicit != "auto":
        return explicit
    devs = list_video_devices()
    if not devs:
        return None
    # 1. uvcvideo 驱动优先（排除内部 ISP / SteamVR / 编解码节点）
    candidates = [(p, n) for p, n in devs if n.endswith("|uvcvideo")]
    if not candidates:
        candidates = [(p, n) for p, n in devs
                      if not any(k in n.lower() for k in
                                 ("msm", "steamvr", "iris", "dec0", "enc0", "isp"))]
    for path, info in candidates:
        caps = probe_caps(path)
        if "Video Capture" not in caps and "MJPG" not in caps and "YUYV" not in caps:
            continue
        fmt, size, fps = parse_first_format(caps)
        log(f"选用摄像头: {path} ({info.split('|')[0]}) 格式={fmt} {size} @{fps}fps")
        return path
    if candidates:
        log(f"选用摄像头: {candidates[0][0]} ({candidates[0][1].split('|')[0]})")
        return candidates[0][0]
    log("未找到可用摄像头节点")
    return None


# ---------------------------------------------------------------------------
#  发送
# ---------------------------------------------------------------------------
class Sender:
    def __init__(self, host: str, port: int, stream_id: int = 0):
        self.addr = (host, port)
        self.stream_id = stream_id & 0xFF
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1 << 20)
        try:
            self.sock.bind(("", 0))      # ★ 接收端按这个源端口回发规格
            self.sock.setblocking(False)
        except Exception:
            pass
        self.cfg = {}                    # 接收端下发的规格
        self.cfg_seq = 0
        self.cfg_dirty = False
        self.restart_req = False
        self.seq = 0
        self.frames = 0
        self.bytes = 0
        self.t0 = time.time()

    def send_frame(self, jpeg: bytes):
        n = len(jpeg)
        fcnt = (n + PAYLOAD_MAX - 1) // PAYLOAD_MAX
        if fcnt == 0 or fcnt > 65535:
            return
        seq = self.seq & 0xFFFFFFFF
        head = MAGIC + bytes([self.stream_id])
        for i in range(fcnt):
            chunk = jpeg[i * PAYLOAD_MAX:(i + 1) * PAYLOAD_MAX]
            pkt = head + struct.pack("<IHH", seq, i, fcnt) + chunk
            try:
                self.sock.sendto(pkt, self.addr)
            except Exception as e:
                log(f"发送失败: {e}")
                return
        self.seq += 1
        self.frames += 1
        self.bytes += n
        if self.frames % 20 == 0:
            self.poll_cfg()

    def send_meta(self, d: dict):
        """元数据包（sid=255）：告诉接收端这台的名称/支持的规格组合。"""
        try:
            body = json.dumps(d, ensure_ascii=False).encode("utf-8")
            pkt = (MAGIC + bytes([255]) + struct.pack("<IHH", self.seq & 0xFFFFFFFF, 0, 1)
                   + body)
            self.sock.sendto(pkt, self.addr)
        except Exception:
            pass

    def poll_cfg(self):
        """非阻塞收接收端下发的规格（回到同一源端口）。"""
        while True:
            try:
                data, _a = self.sock.recvfrom(2048)
            except (BlockingIOError, OSError):
                break
            if not data.startswith(CFG_MAGIC):
                continue
            try:
                d = json.loads(data[len(CFG_MAGIC):].decode("utf-8", "ignore"))
            except Exception:
                continue
            if int(d.get("sid", self.stream_id)) != self.stream_id:
                continue
            self.cfg = d
            self.cfg_seq += 1
            self.cfg_dirty = True
            log(f"[s{self.stream_id}] 收到规格: {d.get('w')}x{d.get('h')}"
                f"@{d.get('fps')}fps {d.get('fmt')}")

    def fps(self) -> float:
        dt = time.time() - self.t0
        return self.frames / dt if dt > 0 else 0.0


def run_ffmpeg(device: str, size: str, fps: int, input_fmt: str = "mjpeg"):
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-fflags", "nobuffer", "-flags", "low_delay",   # 低延迟：不做输入缓冲
        "-f", "v4l2", "-input_format", input_fmt,
        "-video_size", size, "-framerate", str(fps),
        "-i", device,
    ]
    if input_fmt == "mjpeg":
        # ★ 相机本来就输出 MJPEG：原样转发，不重新编码
        #   （重编码会在帧变大时排队，造成延迟越来越高）
        cmd += ["-c:v", "copy", "-f", "mjpeg", "-"]
    else:
        # 非 MJPEG（如 YUYV）才需要编码
        cmd += ["-f", "mjpeg", "-q:v", "5", "-"]
    log("启动: " + " ".join(cmd[:9]) + " …")
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)


def selftest(args) -> int:
    """自检：接管设备 → 收帧 → 主动复位模拟掉线 → 再收帧。
    用于安装器最后一步验证，避免把未验证的版本交给用户。"""
    try:
        import uvc_bulk_sender as bulk
    except ImportError:
        import sys as _s
        _s.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import uvc_bulk_sender as bulk

    usb = bulk.USB()
    sender = Sender("127.0.0.1", 59999, 0)   # 自检不实际发到网络

    def get_frames(seconds: float, want: int = 30) -> int:
        found, _ = usb.find(args.vid, args.pid)
        if not found:
            log("✗ 设备不在总线上")
            return 0
        dev = found[0][0]
        h = None
        try:
            h = usb.open(dev)
            usb.claim(h, args.iface)
            before = sender.frames
            bulk.pump_frames(usb, h, args.ep, sender,
                             stop_after=before + want, duration=seconds)
            return sender.frames - before
        except Exception as e:
            log(f"✗ 自检异常: {e}")
            return 0
        finally:
            if h:
                try:
                    usb.lib.libusb_release_interface(h, args.iface)
                    usb.lib.libusb_attach_kernel_driver(h, args.iface)
                    usb.lib.libusb_close(h)
                except Exception:
                    pass

    log("=== 自检第 1 步：能否取到画面 ===")
    n1 = get_frames(12, want=30)
    log(f"  取到 {n1} 帧")
    if n1 < 10:
        log("SELFTEST: FAIL（取不到画面）")
        return 1

    log(f"SELFTEST: PASS（取到 {n1} 帧）")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true",
                    help="自检：能否取帧 + 复位后能否恢复（安装器会用）")
    ap.add_argument("--to", help="接收端 ip:port（省略则自动搜索）")
    ap.add_argument("--device", default="auto")
    ap.add_argument("--size", default="640x480")
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--stream", default="auto",
                    help="auto=每个相机自动分配一路（多路）；也可写 0-5 强制单路")
    ap.add_argument("--backend", default="auto", choices=["auto", "v4l2", "libusb"],
                    help="auto=先试 v4l2，无帧自动切 libusb（XIAO 这类 Bulk 相机）")
    ap.add_argument("--vid", type=lambda x: int(x, 0), default=0x303A)
    ap.add_argument("--pid", type=lambda x: int(x, 0), default=0x8000)
    ap.add_argument("--iface", type=int, default=3)
    ap.add_argument("--ep", type=lambda x: int(x, 0), default=0x83)
    ap.add_argument("--save-dir", default=None, help="调试：把收到的原始帧存到该目录")
    ap.add_argument("--save-count", type=int, default=0, help="调试：最多存多少帧（0=不限）")
    args = ap.parse_args()

    if args.selftest:
        return selftest(args)

    if args.to:
        host, _, port = args.to.partition(":")
        addr = (host, int(port or 5000))
    else:
        found = discover()
        if not found:
            log("✗ 没找到 PC 接收端。请确认 PC 上已运行 VRCReceiver，且同一局域网。")
            return 2
        addr = found

    # --stream auto（默认）→ 多路：每个相机自动分配一路
    if str(args.stream).lower() in ("auto", "", "multi"):
        return run_multi(addr, args)

    sender = Sender(addr[0], addr[1], int(args.stream))
    log(f"发送到 {addr[0]}:{addr[1]}  stream {args.stream}")

    while True:
        device = pick_camera(args.device)
        if not device:
            log("✗ 没有摄像头，等待插入…（每 3 秒重试）")
            time.sleep(3)
            continue
        rc = serve_device(sender, args, device)
        if rc == RC_RESTART:
            continue
        if rc == RC_LOST:
            log("相机已拔出或更换，重新选择相机…")
            time.sleep(1)
            continue
        return rc


def device_usb_ids(path: str):
    """取某个 /dev/videoN 所属 USB 设备的 (idVendor, idProduct)。"""
    try:
        cur = os.path.realpath(f"/sys/class/video4linux/{os.path.basename(path)}/device")
        for _ in range(6):
            vp, pp = os.path.join(cur, "idVendor"), os.path.join(cur, "idProduct")
            if os.path.exists(vp) and os.path.exists(pp):
                with open(vp) as f:
                    v = int(f.read().strip(), 16)
                with open(pp) as f:
                    p = int(f.read().strip(), 16)
                return v, p
            parent = os.path.dirname(cur)
            if parent == cur:
                break
            cur = parent
    except Exception:
        pass
    return None, None


SID_MAP_FILE = os.path.expanduser("~/.vrcforwarder_streams.json")


def _usb_sysdir(path: str) -> str:
    """找到 /dev/videoN 对应的 USB 设备 sysfs 目录。"""
    try:
        cur = os.path.realpath(f"/sys/class/video4linux/{os.path.basename(path)}/device")
        for _ in range(6):
            if os.path.exists(os.path.join(cur, "idVendor")):
                return cur
            parent = os.path.dirname(cur)
            if parent == cur:
                break
            cur = parent
    except Exception:
        pass
    return ""


def _readfile(p: str) -> str:
    try:
        with open(p) as f:
            return f.read().strip()
    except Exception:
        return ""


def device_serial(path: str) -> str:
    d = _usb_sysdir(path)
    return _readfile(os.path.join(d, "serial")) if d else ""


def device_identity(path: str) -> str:
    """稳定身份：序列号优先，其次物理端口（同型号多台也能区分，重插不变）。"""
    d = _usb_sysdir(path)
    if not d:
        return path
    vid = _readfile(os.path.join(d, "idVendor"))
    pid = _readfile(os.path.join(d, "idProduct"))
    serial = _readfile(os.path.join(d, "serial"))
    if serial:
        return f"{vid}:{pid}#{serial}"
    return f"{vid}:{pid}@{os.path.basename(d)}"


def load_sid_map() -> dict:
    try:
        with open(SID_MAP_FILE) as f:
            d = json.load(f)
            return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def save_sid_map(m: dict):
    try:
        with open(SID_MAP_FILE, "w") as f:
            json.dump(m, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def path_of_identity(ident: str) -> str:
    for p in list_cameras():
        if device_identity(p) == ident:
            return p
    return ""


def _bulk_device_present(args, device: str = "", serial: str = "") -> bool:
    """这台相机本身是不是我们认识的 Bulk 设备（XIAO 303a:8000）。

    libusb 通路只对它有效；普通 UVC 相机 v4l2 失败时不能走它，
    否则会去抢别的设备（表现为 LIBUSB_ERROR_BUSY 刷屏）。
    """
    if device:
        v, p = device_usb_ids(device)
        if v is None or v != args.vid or (args.pid is not None and p != args.pid):
            return False
    try:
        import uvc_bulk_sender as bulk
        usb = bulk.USB()
        return usb.find_latest(args.vid, args.pid, args.iface, args.ep, serial or None) is not None
    except Exception:
        return False


def apply_format_via_v4l2(device: str, size: str, fmt: str) -> bool:
    """用 v4l2-ctl 把设备切到指定格式（会触发 UVC probe/commit）。

    libusb 直读通路本身不改设备格式，必须先用它协商。
    """
    try:
        w, _, h = str(size).partition("x")
        pf = {"MJPG": "MJPG", "YUYV": "YUYV"}.get(str(fmt).upper(), "MJPG")
        r = subprocess.run(["v4l2-ctl", "-d", device,
                            f"--set-fmt-video=width={w},height={h},pixelformat={pf}"],
                           capture_output=True, timeout=10)
        if r.returncode != 0:
            return False
        return True
    except Exception:
        return False


def serve_device(sender: Sender, args, device: str) -> int:
    """为一台设备服务，直到它消失（返回 RC_LOST）或其它原因结束。"""
    a = copy.copy(args)                      # 每路独立：不共享 size/fps
    serial = device_serial(device)            # ★ 同型号多台设备靠它定位
    caps = probe_caps(device)
    fmt, size, fps = parse_first_format(caps)
    dev_fmt = fmt
    if size:
        a.size = size
    if fps:
        a.fps = fps
    # ★ 接收端下发的规格优先（没有就用设备首选）
    c = getattr(sender, "cfg", None) or {}
    if c.get("w") and c.get("h"):
        a.size = f"{int(c['w'])}x{int(c['h'])}"
    if c.get("fps"):
        a.fps = int(c["fps"])
    if c.get("fmt"):
        want = str(c["fmt"]).upper()
        if want in caps.upper():
            fmt = want
        else:
            log(f"[{device}] 设备不支持 {want}，回落到 {dev_fmt or '首选'}")
            fmt = dev_fmt
    if fmt and fmt.upper() != "MJPG":
        log(f"⚠ {device} 首选格式是 {fmt}（非 MJPEG），将尝试 YUYV 转码")
        input_fmt = "yuyv422"
    else:
        input_fmt = "mjpeg"
    log(f"[{device}] {fmt or '未知'} {a.size} @{a.fps}fps → ffmpeg input={input_fmt}")
    try:
        _w, _, _h = str(a.size).partition("x")
        sender.send_meta({"sid": sender.stream_id,
                          "specs": parse_all_formats(caps),
                          "cur": {"w": int(_w), "h": int(_h), "fps": int(a.fps), "fmt": fmt}})
    except Exception:
        pass

    backend = args.backend
    if backend == "auto":
        log(f"[{device}] 尝试 v4l2 通路…")
        frames = run_v4l2_backend(device, a, input_fmt, sender, probe_seconds=4)
        if getattr(sender, "restart_req", False):
            sender.restart_req = False
            return RC_RESTART
        if not os.path.exists(device):
            return RC_LOST
        if frames > 0:
            return 0
        if not _bulk_device_present(a, device, serial):
            log(f"[{device}] v4l2 无帧，且总线上没有匹配 "
                f"{a.vid:04x}:{a.pid:04x} 的 Bulk 相机；稍后重试")
            return 0
        log(f"[{device}] v4l2 无帧，切 libusb 直读…")
        if getattr(sender, "cfg", None):
            apply_format_via_v4l2(device, a.size, fmt)
        _rc = run_libusb_backend(sender, a, device, serial)
        if getattr(sender, "restart_req", False):
            sender.restart_req = False
            return RC_RESTART
        return _rc
    if backend == "libusb":
        if not _bulk_device_present(a, device, serial):
            log(f"[{device}] 没有匹配 {a.vid:04x}:{a.pid:04x} 的 Bulk 相机；稍后重试")
            return 0
        if getattr(sender, "cfg", None):
            apply_format_via_v4l2(device, a.size, fmt)
        _rc = run_libusb_backend(sender, a, device, serial)
        if getattr(sender, "restart_req", False):
            sender.restart_req = False
            return RC_RESTART
        return _rc
    run_v4l2_backend(device, a, input_fmt, sender, probe_seconds=0)
    return 0 if os.path.exists(device) else RC_LOST


def list_cameras() -> list[str]:
    """所有可用于取流的节点（uvcvideo 驱动 + 有 Video Capture 能力）。

    XIAO 这类复合设备会同时枚举「取流节点」和「metadata 节点」，
    必须只保留能真正取流的那一个。
    """
    cams = []
    for path, info in list_video_devices():
        if not info.endswith("|uvcvideo"):
            continue
        caps = probe_caps(path)
        if "Video Capture" not in caps:
            continue
        if "Metadata" in caps:
            continue
        # ★ 复合相机的 metadata 节点也会报 "Video Capture"，但**没有任何格式**
        #   （v4l2-ctl 只输出一行 Type: Video Capture）。用它来排除。
        fmt, size, fps = parse_first_format(caps)
        if not fmt and not size:
            continue
        cams.append(path)
    return sorted(cams)


def _multi_worker(addr, args, ident: str, sid: int):
    sender = Sender(addr[0], addr[1], sid)
    log(f"[stream {sid}] 启动：{ident}")
    while True:
        path = path_of_identity(ident)      # ★ 重插后 /dev/videoN 会变，按身份重新取
        if not path:
            log(f"[stream {sid}] {ident} 已拔出，该路停止")
            return 0
        rc = serve_device(sender, args, path)
        if rc == RC_RESTART:
            continue                    # 按新规格重启这一路
        if rc == RC_LOST:
            log(f"[stream {sid}] {path} 已拔出，该路停止")
            return 0
        if rc in (2, 3, 4):
            log(f"[stream {sid}] 结束（rc={rc}）")
            return rc
        time.sleep(1)


def run_multi(addr, args) -> int:
    """多路模式：按「稳定身份」给每台相机固定一路，插拔不乱序。"""
    import threading
    cleanup_stale_ffmpeg()
    log(f"多路模式：发送到 {addr[0]}:{addr[1]}（每台相机固定一路）")
    sid_map = load_sid_map()            # 身份 → 流号（持久化）
    threads: dict[str, "threading.Thread"] = {}
    last_key = None
    while True:
        try:
            seen = {}
            for p in list_cameras():
                seen.setdefault(device_identity(p), p)
        except Exception as e:
            log(f"扫描相机失败: {e}")
            seen = {}

        # 新设备分配最小空闲流号，并持久化
        used = {sid_map[i] for i in seen if i in sid_map}
        changed = False
        for ident in seen:
            if ident in sid_map:
                continue
            sid = 0
            while sid in used:
                sid += 1
            sid_map[ident] = sid
            used.add(sid)
            changed = True
        if changed:
            save_sid_map(sid_map)
            log("已保存相机→流号映射（重插后保持）")

        # 起线程
        for ident in seen:
            t = threads.get(ident)
            if t is not None and t.is_alive():
                continue
            th = threading.Thread(target=_multi_worker,
                                  args=(addr, args, ident, sid_map[ident]),
                                  daemon=True, name=f"cam-s{sid_map[ident]}")
            threads[ident] = th
            th.start()

        # 回收已结束的
        for ident in [i for i, t in threads.items() if not t.is_alive()]:
            threads.pop(ident, None)

        key = tuple(sorted((i, sid_map[i]) for i in seen))
        if key != last_key:
            if seen:
                log("当前相机: " + ", ".join(f"{i}→stream{sid_map[i]}" for i in seen))
            else:
                log("没有可用相机，等待插入…（每 3 秒扫描）")
            last_key = key
        time.sleep(3)


def _ppid_of(pid: int) -> int:
    try:
        with open(f"/proc/{pid}/stat") as f:
            s = f.read()
        return int(s.rsplit(")", 1)[1].split()[1])
    except Exception:
        return -1


def cleanup_stale_ffmpeg():
    """杀掉本程序遗留的 ffmpeg。

    进程被 systemd 重启或 os.execv 后，子进程 ffmpeg 不会跟着退出，
    会一直占着 /dev/video*（表现为 Device or resource busy，只能插拔）。
    只杀命令行同时含 ffmpeg / -f v4l2 / /dev/video 的进程，不碰别人的。
    """
    me = os.getpid()
    killed = 0
    try:
        for pid in os.listdir("/proc"):
            if not pid.isdigit() or int(pid) == me:
                continue
            try:
                with open(f"/proc/{pid}/cmdline", "rb") as f:
                    cmd = f.read().replace(b"\x00", b" ").decode("utf-8", "ignore")
            except Exception:
                continue
            # 只杀「已经被 systemd/父进程抛弃」的（ppid==1）残留，
            # 绝不碰当前进程自己起的 ffmpeg
            if _ppid_of(int(pid)) != 1:
                continue
            if "ffmpeg" in cmd and "-f v4l2" in cmd and "/dev/video" in cmd:
                try:
                    os.kill(int(pid), 9)
                    killed += 1
                except Exception:
                    pass
    except Exception:
        pass
    if killed:
        log(f"已清理 {killed} 个遗留 ffmpeg 进程")
        time.sleep(0.5)
    return killed


def run_v4l2_backend(device: str, args, input_fmt: str, sender: Sender,
                     probe_seconds: float = 0) -> int:
    proc = run_ffmpeg(device, args.size, args.fps, input_fmt)
    buf = b""
    last_report = time.time()
    try:
        while True:
            chunk = proc.stdout.read(65536)
            if not chunk:
                log("ffmpeg 结束（设备被拔出或参数不支持）")
                err = proc.stderr.read(2000).decode("utf-8", "replace").strip()
                if "resource busy" in err.lower():
                    cleanup_stale_ffmpeg()
                if err:
                    log("ffmpeg: " + err.replace("\n", " ")[:300])
                break
            buf += chunk
            got = []
            while True:
                start = buf.find(SOI)
                if start < 0:
                    if len(buf) > 4 * 1024 * 1024:
                        buf = b""
                    break
                nxt = buf.find(SOI, start + 2)
                if nxt < 0:
                    if start:
                        buf = buf[start:]
                    break
                frame = buf[start:nxt]
                buf = buf[nxt:]
                if len(frame) > 64:
                    got.append(frame)
            if got:
                # ★ 延迟控制：如果 pipe 里还有积压（设备产量 > USB 带宽），
                #   只发最新一帧，把旧帧丢掉 —— 否则延迟会越积越大。
                backlog = False
                try:
                    import select as _sel
                    backlog = bool(_sel.select([proc.stdout], [], [], 0)[0])
                except Exception:
                    backlog = False
                frames_to_send = [got[-1]] if (backlog and len(got) >= 1) else got
                if backlog:
                    sender.dropped = getattr(sender, "dropped", 0) + len(got) - 1
                for frame in frames_to_send:
                    sender.send_frame(frame)
                    if getattr(sender, "cfg_dirty", False):
                        sender.cfg_dirty = False
                        sender.restart_req = True
                        return sender.frames      # 按新规格重启这一路
            now = time.time()
            if probe_seconds and now - (last_report - 0) > probe_seconds and sender.frames == 0:
                break
            if now - last_report >= 2.0:
                last_report = now
                _d = getattr(sender, "dropped", 0)
                log(f"[s{getattr(sender, 'stream_id', 0)}] 已发送 {sender.frames} 帧 · {sender.fps():.1f} fps · "
                    f"{sender.bytes/1024/1024:.1f} MB" + (f"（丢旧帧 {_d}）" if _d else ""))
    except KeyboardInterrupt:
        log("用户中断")
    finally:
        try:
            proc.terminate()
            try:
                proc.wait(timeout=2)
            except Exception:
                proc.kill()
                try:
                    proc.wait(timeout=2)
                except Exception:
                    pass
        except Exception:
            pass
    return sender.frames


LOST_FILE = "/tmp/frame_sender.lost"


def _mark_lost():
    """记录画面断开时刻（跨 execv 重启也能保留，用来量恢复耗时）。"""
    try:
        with open(LOST_FILE, "w") as f:
            f.write(f"{time.time():.3f}")
    except Exception:
        pass


def _report_recovery():
    try:
        with open(LOST_FILE) as f:
            t = float(f.read().strip())
        os.unlink(LOST_FILE)
        log(f"画面恢复，用时 {time.time() - t:.1f} 秒")
    except Exception:
        pass


def arm_uvc(dev_path: str, args) -> bool:
    """给设备发一次 UVC probe/commit（v4l2 的 S_FMT 会发出这两个请求）。

    实测：XIAO 每次重新枚举后，必须收到 probe/commit 才会推流；v4l2-ctl 自己
    可能回 Protocol error，但请求已经发出去了，不影响效果。
    """
    try:
        w, h = (args.size or "240x240").lower().split("x")
        subprocess.run(["v4l2-ctl", "-d", dev_path,
                        f"--set-fmt-video=width={w},height={h},pixelformat=MJPG"],
                       capture_output=True, timeout=6)
        log("  已发送格式协商（probe/commit）")
        return True
    except Exception as e:
        log(f"  格式协商失败: {e}")
        return False


def run_libusb_backend(sender: Sender, args, dev_path: str = "", serial: str = "") -> int:
    """XIAO FaceTracker 这类 USB 全速 Bulk 相机：uvcvideo 播不动，直接读 Bulk 端点。

    ★ 该设备会周期性从总线上掉线（LIBUSB_ERROR_NO_DEVICE），所以必须：
      掉线 → 重新查找设备 → 重新接管 → 继续发送，而不是退出。
    """
    try:
        import uvc_bulk_sender as bulk
    except ImportError:
        import sys as _s
        _s.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import uvc_bulk_sender as bulk

    usb = bulk.USB()
    reconnects = 0
    wait_start = time.time()
    waiting = False
    last_addr = None
    gone_since = 0.0
    while True:
        # ★ 永远接管「最新一次枚举」：设备重枚举后地址递增，取地址最大的那个，
        #   并且要求这次枚举里真的有视频流接口（跳过 ESP32 串口那一次）。
        hit = usb.find_latest(args.vid, args.pid, args.iface, args.ep, serial or None)
        if hit is None:
            if not waiting:
                log("等待视频设备出现…" if reconnects == 0 else "设备不在总线上，等待重新枚举…")
                waiting = True
                gone_since = time.time()
            # ★ 相机被拔掉（换成别的相机/没插）时不要在这里死等：
            #   交回 main 重新扫描 /dev/video*，否则换成传统 UVC 相机永远不出图。
            if reconnects == 0 and time.time() - wait_start > 40:
                log(f"✗ 没找到带视频流接口的设备 {args.vid:04x}:{args.pid:04x}")
                return 4
            if time.time() - gone_since > 8.0:
                log("该相机已不在总线上，重新扫描可用相机…")
                return RC_LOST
            time.sleep(0.2)
            continue

        waiting = False
        dev, v, p = hit
        bus, addr = usb.get_bus_addr(dev)

        # 新的一次枚举：等它初始化完再接管（不要在这里发 probe/commit，
        # 实测那样会把首次启动的正常出图弄坏）。
        if addr != last_addr:
            last_addr = addr
            time.sleep(0.8)

        if reconnects == 0:
            log(f"接管设备 {v:04x}:{p:04x}（总线第 {addr} 号 = 最新枚举），接口 {args.iface}")
        else:
            log(f"接管最新枚举（总线第 {addr} 号），第 {reconnects} 次重连")

        handle = None
        try:
            handle = usb.open(dev)
            usb.claim(handle, args.iface)
            bulk.pump_frames(usb, handle, args.ep, sender,
                             save_dir=args.save_dir, save_count=args.save_count,
                             empty_give_up=2, on_first_frame=_report_recovery)
            log("数据流停止")
            _mark_lost()
        except KeyboardInterrupt:
            log("用户中断")
            return 0 if sender.frames else 4
        except bulk.StuckStream as e:
            # 实测：同一个进程里 claim 落空后永远不会自愈（libusb 上下文被设备掉线污染），
            # 而新进程接管立刻恢复。这里直接就地重启自己（execv），比等 systemd 快 1-2 秒。
            log(f"数据流卡住（{e}）→ 就地重启发送进程")
            _mark_lost()
            for f in (sys.stdout, sys.stderr):
                try:
                    f.flush()
                except Exception:
                    pass
            os.execv(sys.executable, [sys.executable] + sys.argv)
        except Exception as e:
            log(f"读取中断: {e}")
            _mark_lost()
        finally:
            if handle:
                try:
                    usb.lib.libusb_release_interface(handle, args.iface)
                    usb.lib.libusb_attach_kernel_driver(handle, args.iface)
                    usb.lib.libusb_close(handle)
                except Exception:
                    pass

        reconnects += 1
        if reconnects > 1000:
            log("重连次数过多，退出")
            break
        time.sleep(0.2)      # 立刻去找最新枚举，不用等

    log(f"共发送 {sender.frames} 帧，{sender.bytes/1024/1024:.2f} MB")
    return 0 if sender.frames else 4


if __name__ == "__main__":
    sys.exit(main())
