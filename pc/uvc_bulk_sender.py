#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UVC Bulk 直读发送端（给 XIAO FaceTracker 这类「USB 全速 Bulk」设备用）

背景：这类设备 uvcvideo 能枚举、但 VIDIOC_STREAMON 返回 -ENOMEM
（Linux uvcvideo 的 bulk 流路径与它的描述符不兼容）。
绕过办法：直接用 libusb 打开设备、摘掉内核驱动、自己读 Bulk IN 端点，
按 UVC payload header 组帧，再走与本项目相同的 UDP 协议。

只依赖系统自带的 libusb-1.0.so.0（ctypes 调用，无需 pip 装东西）。

用法：
    uvc_bulk_sender.py --to 192.168.11.130:5000
    uvc_bulk_sender.py --vid 0x303a --pid 0x8000 --iface 3 --ep 0x83
"""
from __future__ import annotations

import argparse
import ctypes
import ctypes.util
import os
import socket
import struct
import sys
import time

MAGIC = b"UV"
HEADER_LEN = 11
PAYLOAD_MAX = 1200

SOI = b"\xff\xd8"


def log(msg: str):
    print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


def trace(msg: str):
    """内部诊断信息（不打印到 stdout，避免刷屏）。"""
    if os.environ.get("UVC_DEBUG"):
        print(time.strftime("[%H:%M:%S] [trace] ") + msg, flush=True)


# ===========================================================================
#  libusb 绑定
# ===========================================================================
class _EndpointDesc(ctypes.Structure):
    _fields_ = [("bLength", ctypes.c_uint8), ("bDescriptorType", ctypes.c_uint8),
                ("bEndpointAddress", ctypes.c_uint8), ("bmAttributes", ctypes.c_uint8),
                ("wMaxPacketSize", ctypes.c_uint16), ("bInterval", ctypes.c_uint8),
                ("bRefresh", ctypes.c_uint8), ("bSynchAddress", ctypes.c_uint8),
                ("extra", ctypes.c_void_p), ("extra_length", ctypes.c_int)]


class _IfaceDesc(ctypes.Structure):
    _fields_ = [("bLength", ctypes.c_uint8), ("bDescriptorType", ctypes.c_uint8),
                ("bInterfaceNumber", ctypes.c_uint8), ("bAlternateSetting", ctypes.c_uint8),
                ("bNumEndpoints", ctypes.c_uint8), ("bInterfaceClass", ctypes.c_uint8),
                ("bInterfaceSubClass", ctypes.c_uint8), ("bInterfaceProtocol", ctypes.c_uint8),
                ("iInterface", ctypes.c_uint8),
                ("endpoint", ctypes.POINTER(_EndpointDesc)),
                ("extra", ctypes.c_void_p), ("extra_length", ctypes.c_int)]


class _Iface(ctypes.Structure):
    _fields_ = [("altsetting", ctypes.POINTER(_IfaceDesc)),
                ("num_altsetting", ctypes.c_int)]


class _ConfigDesc(ctypes.Structure):
    _fields_ = [("bLength", ctypes.c_uint8), ("bDescriptorType", ctypes.c_uint8),
                ("wTotalLength", ctypes.c_uint16), ("bNumInterfaces", ctypes.c_uint8),
                ("bConfigurationValue", ctypes.c_uint8), ("iConfiguration", ctypes.c_uint8),
                ("bmAttributes", ctypes.c_uint8), ("MaxPower", ctypes.c_uint8),
                ("interface", ctypes.POINTER(_Iface)),
                ("extra", ctypes.c_void_p), ("extra_length", ctypes.c_int)]


class USB:
    """最小 libusb-1.0 绑定（只用需要的几个函数）。"""

    def __init__(self):
        name = ctypes.util.find_library("usb-1.0") or "libusb-1.0.so.0"
        self.lib = ctypes.CDLL(name)
        L = self.lib
        L.libusb_init.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
        L.libusb_init.restype = ctypes.c_int
        L.libusb_exit.argtypes = [ctypes.c_void_p]
        L.libusb_get_device_list.argtypes = [ctypes.c_void_p,
                                             ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))]
        L.libusb_get_device_list.restype = ctypes.c_ssize_t
        L.libusb_free_device_list.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_int]
        L.libusb_get_device_descriptor.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        L.libusb_open.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
        L.libusb_open.restype = ctypes.c_int
        L.libusb_close.argtypes = [ctypes.c_void_p]
        L.libusb_claim_interface.argtypes = [ctypes.c_void_p, ctypes.c_int]
        L.libusb_claim_interface.restype = ctypes.c_int
        L.libusb_release_interface.argtypes = [ctypes.c_void_p, ctypes.c_int]
        L.libusb_set_interface_alt_setting.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
        L.libusb_set_interface_alt_setting.restype = ctypes.c_int
        L.libusb_detach_kernel_driver.argtypes = [ctypes.c_void_p, ctypes.c_int]
        L.libusb_detach_kernel_driver.restype = ctypes.c_int
        L.libusb_attach_kernel_driver.argtypes = [ctypes.c_void_p, ctypes.c_int]
        L.libusb_attach_kernel_driver.restype = ctypes.c_int
        L.libusb_bulk_transfer.argtypes = [
            ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_char_p,
            ctypes.c_int, ctypes.POINTER(ctypes.c_int), ctypes.c_uint]
        L.libusb_bulk_transfer.restype = ctypes.c_int
        L.libusb_clear_halt.argtypes = [ctypes.c_void_p, ctypes.c_ubyte]
        L.libusb_clear_halt.restype = ctypes.c_int
        L.libusb_reset_device.argtypes = [ctypes.c_void_p]
        L.libusb_reset_device.restype = ctypes.c_int
        L.libusb_error_name.argtypes = [ctypes.c_int]
        L.libusb_error_name.restype = ctypes.c_char_p
        self.ctx = ctypes.c_void_p()
        if L.libusb_init(ctypes.byref(self.ctx)) != 0:
            raise RuntimeError("libusb_init 失败")

    def error_name(self, code: int) -> str:
        try:
            n = self.lib.libusb_error_name(code)
            return n.decode() if n else str(code)
        except Exception:
            return str(code)

    def find(self, vid: int, pid: int | None):
        # 释放上一次的 device list：设备重枚举后必须重新获取，否则会一直看到旧设备
        try:
            if getattr(self, "_dl", None):
                self.lib.libusb_free_device_list.argtypes = [ctypes.c_void_p, ctypes.c_int]
                self.lib.libusb_free_device_list(self._dl, 1)
        except Exception:
            pass
        self._dl = None
        devs = ctypes.POINTER(ctypes.c_void_p)()
        n = self.lib.libusb_get_device_list(self.ctx, ctypes.byref(devs))
        found = []
        for i in range(max(0, n)):
            dev = devs[i]
            desc = ctypes.create_string_buffer(18)
            if self.lib.libusb_get_device_descriptor(dev, desc) == 0:
                raw = bytes(desc.raw[:18])
                v = raw[8] | (raw[9] << 8)
                p = raw[10] | (raw[11] << 8)
                if v == vid and (pid is None or p == pid):
                    found.append((dev, v, p))
        # 注意：不立即释放，保持 dev 指针有效；下一次 find() 会释放它
        self._dl = devs
        return found, devs

    def open(self, dev):
        h = ctypes.c_void_p()
        rc = self.lib.libusb_open(dev, ctypes.byref(h))
        if rc != 0:
            raise RuntimeError(f"libusb_open 失败: {self.error_name(rc)}")
        return h

    def has_stream_iface(self, dev, iface: int, ep: int) -> bool:
        """这次枚举里是否真的有 UVC 流接口（指定接口号 + Bulk IN 端点）。

        XIAO 插上后会枚举两次：先 ESP32 串口，再重枚举成 UVC。
        必须跳过第一次，否则会守着没有数据流的句柄空读。
        """
        try:
            self.lib.libusb_get_active_config_descriptor.argtypes = [
                ctypes.c_void_p, ctypes.POINTER(ctypes.POINTER(_ConfigDesc))]
            self.lib.libusb_get_active_config_descriptor.restype = ctypes.c_int
            self.lib.libusb_free_config_descriptor.argtypes = [ctypes.POINTER(_ConfigDesc)]
            cfg = ctypes.POINTER(_ConfigDesc)()
            rc = self.lib.libusb_get_active_config_descriptor(dev, ctypes.byref(cfg))
            if rc != 0 or not cfg:
                return False
            try:
                c = cfg.contents
                for i in range(c.bNumInterfaces):
                    itf = c.interface[i]
                    for a in range(itf.num_altsetting):
                        alt = itf.altsetting[a]
                        if alt.bInterfaceNumber != iface:
                            continue
                        for e in range(alt.bNumEndpoints):
                            epd = alt.endpoint[e]
                            if epd.bEndpointAddress == ep and (epd.bmAttributes & 0x03) == 2:
                                return True
            finally:
                self.lib.libusb_free_config_descriptor(cfg)
        except Exception as e:
            log(f"  读配置描述符异常: {e}")
        return False

    def get_bus_addr(self, dev):
        self.lib.libusb_get_bus_number.argtypes = [ctypes.c_void_p]
        self.lib.libusb_get_bus_number.restype = ctypes.c_int
        self.lib.libusb_get_device_address.argtypes = [ctypes.c_void_p]
        self.lib.libusb_get_device_address.restype = ctypes.c_int
        return self.lib.libusb_get_bus_number(dev), self.lib.libusb_get_device_address(dev)

    def serial_of(self, dev) -> str:
        """读设备序列号（iSerialNumber）。同型号多台设备靠它区分。"""
        try:
            L = self.lib
            L.libusb_get_device_descriptor.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
            h = ctypes.c_void_p()
            if L.libusb_open(dev, ctypes.byref(h)) != 0:
                return ""
            try:
                L.libusb_get_string_descriptor_ascii.argtypes = [
                    ctypes.c_void_p, ctypes.c_uint8, ctypes.c_char_p, ctypes.c_int]
                L.libusb_get_string_descriptor_ascii.restype = ctypes.c_int
                buf = ctypes.create_string_buffer(256)
                n = L.libusb_get_string_descriptor_ascii(h, 3, buf, 255)  # 3 = iSerialNumber（XIAO 实测）
                if n <= 0:
                    return ""
                return buf.value.decode("utf-8", "ignore").strip()
            finally:
                L.libusb_close(h)
        except Exception:
            return ""

    def find_latest(self, vid: int, pid: int | None, iface: int | None = None,
                    ep: int | None = None, serial: str | None = None):
        """取「最新一次枚举」出来的那一个设备。

        XIAO 插上后会枚举两次（先串口、再 UVC），拔插还可能再来几轮。
        设备地址每次重枚举都会递增，所以取地址最大的那个 = 最新枚举。
        同时要求这次枚举里真的有视频流接口，避免抢到串口那一次。

        ★ serial 非空时，只返回序列号匹配的那一台（同型号多台设备必须靠它区分）。
        """
        found, _dl = self.find(vid, pid)
        cands = []
        for dev, v, p in found:
            if iface is not None and not self.has_stream_iface(dev, iface, ep):
                continue
            if serial:
                if self.serial_of(dev) != serial:
                    continue
            bus, addr = self.get_bus_addr(dev)
            cands.append((addr, bus, dev, v, p))
        if not cands:
            return None
        cands.sort(key=lambda x: (x[0], x[1]))
        _, _, dev, v, p = cands[-1]
        return dev, v, p

    def claim(self, h, iface: int):
        rc = self.lib.libusb_detach_kernel_driver(h, iface)
        if rc not in (0, -5):  # -5 = NOT_FOUND（没挂内核驱动）
            log(f"  摘除内核驱动返回 {self.error_name(rc)}")
        rc = self.lib.libusb_claim_interface(h, iface)
        if rc != 0:
            raise RuntimeError(f"claim_interface({iface}) 失败: {self.error_name(rc)}")

    def clear_halt(self, h, ep: int):
        """重连后端点可能处于 halt 状态，不清掉就永远读不到数据。"""
        try:
            rc = self.lib.libusb_clear_halt(h, ep)
            if rc != 0:
                log(f"  clear_halt({hex(ep)}) 返回 {self.error_name(rc)}")
            else:
                log(f"  clear_halt({hex(ep)}) ok")
        except Exception as e:
            log(f"  clear_halt 异常: {e}")

    def set_alt(self, h, iface: int, alt: int = 0):
        """UVC Bulk 设备需要 SET_INTERFACE 才会开始推流（重新枚举后尤其必要）。"""
        try:
            rc = self.lib.libusb_set_interface_alt_setting(h, iface, alt)
            log(f"  set_interface({iface},{alt}) -> {self.error_name(rc)}")
            return rc == 0
        except Exception as e:
            log(f"  set_interface 异常: {e}")
            return False

    def reset_device(self, h) -> bool:
        """USB 端口复位：设备掉线/重枚举后端点常处于僵死状态，复位能真正救回来。
        注意：reset 后原 handle 失效，必须重新 open。"""
        try:
            rc = self.lib.libusb_reset_device(h)
            log(f"  reset_device -> {self.error_name(rc)}")
            return rc == 0
        except Exception as e:
            log(f"  reset_device 异常: {e}")
            return False

    def bulk_in(self, h, ep: int, size: int, timeout_ms: int = 1000):
        buf = ctypes.create_string_buffer(size)
        got = ctypes.c_int(0)
        rc = self.lib.libusb_bulk_transfer(h, ep, buf, size, ctypes.byref(got), timeout_ms)
        if rc == 0:
            return buf.raw[:got.value]
        if rc == -7:      # TIMEOUT
            return b""
        raise RuntimeError(f"bulk_transfer 失败: {self.error_name(rc)}")


# ===========================================================================
#  UDP 发送
# ===========================================================================
class StuckStream(Exception):
    """已接管接口但长时间读不到数据：libusb 上下文已被污染，只能换新进程。"""


class Sender:
    def __init__(self, host: str, port: int, stream_id: int = 0):
        self.addr = (host, port)
        self.stream_id = stream_id & 0xFF
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1 << 20)
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
            try:
                self.sock.sendto(head + struct.pack("<IHH", seq, i, fcnt) + chunk, self.addr)
            except Exception as e:
                log(f"发送失败: {e}")
                return
        self.seq += 1
        self.frames += 1
        self.bytes += n

    def fps(self) -> float:
        dt = time.time() - self.t0
        return self.frames / dt if dt > 0 else 0.0


# ===========================================================================
#  UVC payload 组帧
# ===========================================================================
def pump_frames(usb: USB, handle, ep: int, sender: Sender,
                stop_after: int | None = None, duration: float | None = None,
                save_dir: str | None = None, save_count: int = 0,
                packet_size: int = 64, empty_give_up: int = 0,
                on_first_frame=None):
    """
    实测本设备（XIAO FaceTracker, USB 全速 Bulk, wMaxPacketSize=64）：

        每个 64 字节 USB 包 = [2 字节 UVC 头] + [62 字节图像数据]

    两个必须遵守的点：
      1. libusb 一次大读取会把多个包合并返回，且**可能返回短包**（不足 64 字节）。
         一旦按「固定 64 字节切分」就会永久错位 → 画面先正常后突然全乱。
         ⇒ 因此**每次只读 64 字节（= 一个包）**，天然对齐，彻底避免漂移。
      2. 剥头后用连续字节流按 ffd8/ffd9 切帧。
    """
    t_start = time.time()
    empty_reads = 0
    last_report = time.time()
    last_frame_at = time.time()
    errors = 0
    skipped = 0
    saved = 0
    stream = bytearray()
    read_size = packet_size          # ★ 关键：一次只读一个包
    stall_limit = 8.0                # ★ 8 秒没出帧才判定僵死（给设备足够启动时间）

    while True:
        if duration and time.time() - t_start > duration:
            break
        if stop_after and sender.frames >= stop_after:
            break
        try:
            chunk = usb.bulk_in(handle, ep, read_size, timeout_ms=800)
        except Exception as e:
            errors += 1
            if errors <= 3:
                log(f"  读取出错: {e}")
            if errors > 50:
                break
            continue
        if not chunk:
            # claim 落空（例如抢在重枚举窗口里）：这种 claim 不会自愈，
            # 关掉句柄让上层去抢「最新一次枚举」，即可立刻恢复。
            empty_reads += 1
            if empty_give_up and empty_reads >= empty_give_up:
                raise StuckStream(f"连续 {empty_reads} 次空读")
            continue
        empty_reads = 0

        # 一个 chunk = 一个完整的 USB 包：[hlen][header...][payload]
        hlen = chunk[0]
        if 2 <= hlen <= 8 and hlen < len(chunk):
            stream += chunk[hlen:]
        else:
            # 包头异常（极少见）：跳过该包含量，重新对齐
            skipped += 1
            continue

        # 从流里切出完整 JPEG
        while True:
            soi = stream.find(b"\xff\xd8")
            if soi < 0:
                if len(stream) > 8 * 1024 * 1024:
                    stream.clear()
                break
            eoi = stream.find(b"\xff\xd9", soi + 2)
            if eoi < 0:
                if soi > 0:
                    del stream[:soi]
                break
            frame = bytes(stream[soi:eoi + 2])
            del stream[:eoi + 2]
            if len(frame) < 256:
                continue
            if save_dir and (save_count <= 0 or saved < save_count):
                try:
                    os.makedirs(save_dir, exist_ok=True)
                    with open(os.path.join(save_dir, f"frame_{saved:04d}.jpg"), "wb") as f:
                        f.write(frame)
                    saved += 1
                    log(f"  已存帧 {saved}/{save_count or '∞'}（{len(frame)} 字节）")
                except Exception as e:
                    log(f"  存帧失败: {e}")
            sender.send_frame(frame)
            if getattr(sender, "cfg_dirty", False):
                sender.cfg_dirty = False
                sender.restart_req = True
                return sender.frames        # 按新规格重启这一路
            last_frame_at = time.time()
            if on_first_frame is not None:
                cb, on_first_frame = on_first_frame, None
                try:
                    cb()
                except Exception:
                    pass

        now = time.time()
        if now - last_report >= 2.0:
            last_report = now
            log(f"[s{getattr(sender, 'stream_id', 0)}] 已发送 {sender.frames} 帧 · {sender.fps():.1f} fps · "
                f"{sender.bytes/1024/1024:.1f} MB"
                + (f"（跳过异常包 {skipped}）" if skipped else ""))
    return sender.frames


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--to", required=True, help="接收端 ip:port")
    ap.add_argument("--vid", type=lambda x: int(x, 0), default=0x303A)
    ap.add_argument("--pid", type=lambda x: int(x, 0), default=0x8000)
    ap.add_argument("--iface", type=int, default=3)
    ap.add_argument("--ep", type=lambda x: int(x, 0), default=0x83)
    ap.add_argument("--stream", type=int, default=0)
    ap.add_argument("--frames", type=int, default=0, help="发送多少帧后退出（0=一直跑）")
    ap.add_argument("--seconds", type=float, default=0, help="运行多少秒后退出（0=一直跑）")
    args = ap.parse_args()

    host, _, port = args.to.partition(":")
    sender = Sender(host, int(port or 5000), args.stream)

    usb = USB()
    found, devlist = usb.find(args.vid, args.pid)
    if not found:
        log(f"✗ 没找到设备 {args.vid:04x}:{args.pid:04x}")
        return 2
    dev, v, p = found[0]
    log(f"找到设备 {v:04x}:{p:04x}")

    handle = usb.open(dev)
    usb.claim(handle, args.iface)
    log(f"已接管接口 {args.iface}，端点 0x{args.ep:02x}")

    try:
        pump_frames(usb, handle, args.ep, sender,
                    stop_after=args.frames or None,
                    duration=args.seconds or None)
    except KeyboardInterrupt:
        log("中断")
    finally:
        try:
            usb.lib.libusb_release_interface(handle, args.iface)
            usb.lib.libusb_attach_kernel_driver(handle, args.iface)
            usb.lib.libusb_close(handle)
        except Exception:
            pass

    log(f"共发送 {sender.frames} 帧，{sender.bytes/1024/1024:.2f} MB")
    return 0 if sender.frames else 4


if __name__ == "__main__":
    sys.exit(main())
