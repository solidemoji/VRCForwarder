# -*- coding: utf-8 -*-
"""
VRCReceiver 协议层 — UDP 多路流协议/重组/beacon 发现（被 vrc_receiver.py 复用）

协议 v2 分片头: magic 'UV'(2) + stream_id(1) + seq(u32 LE @3) + fidx(u16 LE @7) + fcnt(u16 LE @9) = 11B
功能:
  1. 监听 UDP 5000，按 stream_id 分离多路摄像头流，各自重组/显示/统计
  2. 响应 Android"自动搜索"(UDP 5001)：单播 + 全局广播 + 子网广播 三路应答
  3. 每路独立窗口: "Cam 0" / "Cam 1" / ...
用法:
  python vrc_protocol.py [--port 5000] [--scale 2]
按键: q 退出
"""
import argparse
import socket
import struct
import threading
import time

try:
    import cv2
except ImportError:
    import cv2_shim as cv2
import numpy as np

MAGIC = b"UV"
HEADER_LEN = 11          # magic2 + stream_id1 + seq4 + fidx2 + fcnt2
PAYLOAD_MAX = 1200
BEACON_PORT = 5001
DISCOVER_MSG = b"UVC-FORWARDER-DISCOVER"
REPLY_PREFIX = b"UVC-RECEIVER "

MAX_STREAMS = 6


class Reassembler:
    """单流分片重组器: 同一 seq 的片收齐拼成一帧; 超过 1s 未收齐视为丢帧"""

    def __init__(self):
        self._parts = {}
        self._last = {}
        self._stale_s = 1.0

    def push(self, seq, fidx, fcnt, data):
        now = time.time()
        self._gc(now)
        if seq in self._parts:
            self._parts[seq][fidx] = data
        else:
            self._parts[seq] = {fidx: data}
        self._last[seq] = now
        p = self._parts[seq]
        if len(p) == fcnt:
            buf = b"".join(p[i] for i in range(fcnt))
            del self._parts[seq]
            del self._last[seq]
            return buf
        return None

    def _gc(self, now):
        stale = [s for s, t in self._last.items() if now - t > self._stale_s]
        for s in stale:
            del self._parts[s]
            del self._last[s]

    def reset(self):
        self._parts.clear()
        self._last.clear()


class Stream:
    """一路摄像头的接收状态"""

    def __init__(self, sid, scale):
        self.sid = sid
        self.reasm = Reassembler()
        self.last_seq = -1
        self.stats = {"frames": 0, "lost": 0, "dup": 0, "bad": 0, "bytes": 0}
        self.t0 = time.time()
        self.window = f"Cam {sid}"
        cv2.namedWindow(self.window, cv2.WINDOW_AUTOSIZE)
        self.scale = scale

    def handle_packet(self, seq, fidx, fcnt, payload, key_state):
        """返回 True 表示显示了一帧（供按键检测）"""
        if self.last_seq >= 0:
            if seq < self.last_seq and (self.last_seq - seq) < 1000:
                self.stats["dup"] += 1
                return False
            if seq > self.last_seq + 1000:   # 发送端重启
                self.reasm.reset()
                print(f"[Cam {self.sid}] 检测到发送端重启(序号跳变)，清空重组缓存")
        jpeg = self.reasm.push(seq, fidx, fcnt, payload)
        if jpeg is None:
            return False
        self.last_seq = seq
        frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
        if frame is None:
            self.stats["lost"] += 1
            return False
        self.stats["frames"] += 1
        self.stats["bytes"] += len(jpeg)
        now = time.time()
        if now - self.t0 >= 1.0:
            fps = self.stats["frames"] / (now - self.t0)
            mbps = self.stats["bytes"] * 8 / (now - self.t0) / 1e6
            print(f"[Cam {self.sid}] {fps:.0f} fps · {frame.shape[1]}x{frame.shape[0]} · "
                  f"{mbps:.2f} Mbps · 坏包{self.stats['bad']} 迟到{self.stats['dup']} "
                  f"解码失败{self.stats['lost']}")
            self.stats = {"frames": 0, "lost": 0, "dup": 0, "bad": 0, "bytes": 0}
            self.t0 = now
        if self.scale != 1.0:
            frame = cv2.resize(frame, None, fx=self.scale, fy=self.scale,
                               interpolation=cv2.INTER_NEAREST)
        cv2.imshow(self.window, frame)
        key = cv2.waitKey(1) & 0xFF
        key_state[0] = key
        return True

    def close(self):
        cv2.destroyWindow(self.window)


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        return socket.gethostbyname(socket.gethostname())


def beacon_loop():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # 不用 SO_REUSEADDR(Windows 双 bind 劫持坑)，端口被占直接报错
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind(("0.0.0.0", BEACON_PORT))
    my_ip = local_ip()
    subnet_bc = "255.255.255.255"
    try:
        parts = [int(x) for x in my_ip.split(".")]
        subnet_bc = ".".join(str(parts[i] if i < 3 else 255) for i in range(4))
    except Exception:
        pass
    print(f"[自动搜索] 应答服务已启动 (本机 IP: {my_ip}, 子网广播 {subnet_bc})")
    while True:
        try:
            data, addr = s.recvfrom(512)
            if data.startswith(DISCOVER_MSG):
                reply = REPLY_PREFIX + my_ip.encode()
                # 三路齐发：单播 + 有限广播 + 子网广播（兼容 AP 客户端隔离）
                s.sendto(reply, addr)
                try:
                    s.sendto(reply, ("255.255.255.255", BEACON_PORT))
                    s.sendto(reply, (subnet_bc, BEACON_PORT))
                except Exception:
                    pass
                print(f"[自动搜索] 已应答 {addr[0]} (单播+广播)")
        except Exception as e:
            print(f"[自动搜索] 异常: {e}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--scale", type=float, default=2.0)
    args = ap.parse_args()

    threading.Thread(target=beacon_loop, daemon=True).start()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # 不用 SO_REUSEADDR(Windows 双 bind 劫持坑)
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(0.2)
    print(f"[接收] 监听 UDP {args.port}，等待 Android 端多路转发... (按 q 退出)")

    streams = {}          # sid -> Stream
    key_state = [0]

    try:
        while True:
            try:
                data, _ = sock.recvfrom(PAYLOAD_MAX + HEADER_LEN + 16)
            except socket.timeout:
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break
                continue
            except ConnectionResetError:
                continue  # Windows UDP ICMP 重置噪声，忽略
            except OSError:
                break

            if len(data) < HEADER_LEN or data[:2] != MAGIC:
                continue
            sid = data[2]
            seq, fidx, fcnt = struct.unpack_from("<IHH", data, 3)
            payload = data[HEADER_LEN:]
            if sid >= MAX_STREAMS:
                continue
            if sid not in streams:
                streams[sid] = Stream(sid, args.scale)
                print(f"[接收] 发现新视频流: Cam {sid} (共 {len(streams)} 路)")
            try:
                streams[sid].handle_packet(seq, fidx, fcnt, payload, key_state)
            except Exception as e:
                print(f"[Cam {sid}] 处理异常: {e}")
            if key_state[0] == ord("q"):
                break
    finally:
        for st in streams.values():
            try:
                st.close()
            except Exception:
                pass
        cv2.destroyAllWindows()
        sock.close()
        print("[接收] 已退出")


if __name__ == "__main__":
    main()
