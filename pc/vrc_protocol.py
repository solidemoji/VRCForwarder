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

# ★ 反向控制通道：接收端 → 发送端 下发相机规格
#   b"UC" + JSON {"sid":0,"w":640,"h":480,"fps":30,"fmt":"MJPG"}
CFG_MAGIC = b"UC"


def pack_cfg(d) -> bytes:
    import json
    return CFG_MAGIC + json.dumps(d, ensure_ascii=False).encode("utf-8")


def unpack_cfg(data: bytes):
    """解析规格包；不是规格包/non-JSON 返回 None。"""
    import json
    if not data or not data.startswith(CFG_MAGIC):
        return None
    try:
        d = json.loads(data[len(CFG_MAGIC):].decode("utf-8", "ignore"))
        return d if isinstance(d, dict) else None
    except Exception:
        return None
BEACON_PORT = 5001
# ★★ 应答口的**候选组**：5001 常被别的软件占用（实测客户机上被 NI 的
#    lktsrv.exe / lkTimeSync「NI Time Synchronization」占着），绑不上就往下顺延。
#    App 侧会对同一组端口逐一发 DISCOVER，谁应答就用谁 —— 这样谁占 5001 都不影响。
#    注意：5011-5019 与视频口候选（5000/5002-5010）互不重叠。
BEACON_PORT_CANDIDATES = [5001] + list(range(5011, 5020))
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


def beacon_loop(video_port=None, on_event=None):
    """自动搜索应答。

    video_port: 实际监听的 UDP 视频端口 —— 可以是 int 或返回 int 的可调用对象
                （接收端绑定是异步的，端口在绑定成功后才确定）。
    on_event:   可选回调 (kind, detail) —— 用于把状态显示到界面上，便于诊断"搜不到"：
                kind = "listening" / "bind_fail" / "answered"
    ★ 为什么要带端口：接收端在 5000 被占用时会**自动顺延**到别的端口，
      若应答里只有 IP，App 会一直往 5000 发 → 完全没有画面（用户实测报障）。
      为兼容旧 App：端口等于默认值(5000)时，应答格式保持原样（只有 IP）。
    """
    def emit(kind, detail=""):
        if on_event:
            try:
                on_event(kind, detail)
            except Exception:
                pass

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # 不用 SO_REUSEADDR(Windows 双 bind 劫持坑)，端口被占直接换下一个
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    # ★★ 依次尝试候选口：5001 常被别的软件占（实测客户机上被 NI 的 lktsrv.exe 占着），
    #    绑不上就顺延到 5011..5019。App 会对同一组端口逐一发 DISCOVER，因此照样能搜到。
    bound_port = None
    last_err = None
    for cand in BEACON_PORT_CANDIDATES:
        try:
            s.bind(("0.0.0.0", cand))
            bound_port = cand
            break
        except OSError as e:
            last_err = e
    if bound_port is None:
        # 整组都被占（极罕见）—— 别静默死掉，必须让用户看见
        emit("bind_fail", str(last_err))
        print(f"[自动搜索] ✗ 候选端口全部被占用（自动搜索不可用）: {last_err}")
        return
    my_ip = local_ip()
    subnet_bc = "255.255.255.255"
    try:
        parts = [int(x) for x in my_ip.split(".")]
        subnet_bc = ".".join(str(parts[i] if i < 3 else 255) for i in range(4))
    except Exception:
        pass
    emit("listening", f"{my_ip}" + ("" if bound_port == BEACON_PORT
                                    else f"，应答口 {bound_port}（{BEACON_PORT} 被占）"))
    print(f"[自动搜索] 应答服务已启动 (端口 {bound_port}, 本机 IP: {my_ip}, "
          f"子网广播 {subnet_bc})")
    while True:
        try:
            data, addr = s.recvfrom(512)
            if data.startswith(DISCOVER_MSG):
                try:
                    vp = int(video_port() if callable(video_port) else video_port)
                except Exception:
                    vp = 5000
                host = my_ip if vp == 5000 else f"{my_ip}:{vp}"
                reply = REPLY_PREFIX + host.encode()
                # 三路齐发：单播 + 有限广播 + 子网广播（兼容 AP 客户端隔离）
                s.sendto(reply, addr)
                try:
                    s.sendto(reply, ("255.255.255.255", BEACON_PORT))
                    s.sendto(reply, (subnet_bc, BEACON_PORT))
                except Exception:
                    pass
                print(f"[自动搜索] 已应答 {addr[0]} (单播+广播)")
                emit("answered", addr[0])
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
