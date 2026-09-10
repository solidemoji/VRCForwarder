# -*- coding: utf-8 -*-
"""
VRCReceiver 接收端 — 多路 UVC 推流桌面接收客户端（PySide6 UI）

配合 Android 端 VRCForwarder App 使用：
  - 监听 UDP 5000，按 stream-id 分离多路摄像头流（复用 vrc_protocol.py 的协议/重组逻辑）
  - 每路一个画面面板，可手动开关显示
  - 检测到新推流时弹出系统通知提示
  - 响应 Android「自动搜索」(UDP 5001) —— 由 receiver.py 的 beacon_loop 守护线程提供
  - 内置 MJPEG over HTTP 桥（默认 8080），供 Baballonia/OBS 等桌面软件拉流

用法:
  python vrc_receiver.py [--port 5000] [--lang zh/en/ja/ko/ru]
  （需要 pip install pyside6；虚拟摄像头另需 pip install pyvirtualcam）
"""
import argparse
import json
import os
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
# 图像处理：优先用完整 opencv（开发环境），不可用时回退到轻量 shim（打包环境，
# 体积从 98MB 降到 ~5MB）——两者 API 调用方式一致
try:
    import cv2
except ImportError:
    import cv2_shim as cv2

# Windows 默认定时器粒度 15.6ms，会让 60fps(16.7ms) 的 sleep 节流抖动成
# 15.6/31.2ms 交替 → 视觉像 30fps 卡顿。提高到 1ms 精度。
if os.name == "nt":
    try:
        import ctypes
        ctypes.windll.winmm.timeBeginPeriod(1)
    except Exception:
        pass

from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtGui import QAction, QColor, QImage, QPainter, QPalette, QPixmap
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QLabel, QVBoxLayout, QHBoxLayout,
    QPushButton, QScrollArea, QGridLayout, QSystemTrayIcon, QMenu,
    QStyle, QFrame, QLineEdit,
)

import vrc_protocol as receiver  # 复用: MAGIC/HEADER_LEN/Reassembler/beacon_loop/local_ip 等
import i18n      # 多语言(跟随系统语言, 支持 zh/en/ja/ko/ru, 其它回退英语)
from i18n import t

MAX_STREAMS = receiver.MAX_STREAMS


# =====================================================================
# 接收线程: UDP 收包 + 按流重组 + 统计（解码/显示留在主线程）
# =====================================================================
class StreamWorker(QThread):
    frame_ready = Signal(int, bytes)          # sid, jpeg
    new_stream = Signal(int)                  # 首次收到某 sid 的帧
    meta_ready = Signal(int, str, str, str)   # sid, name, kind, key(稳定设备key)
    stats_ready = Signal(int, str)            # sid, 统计文本
    log_line = Signal(str)                    # 界面日志

    def __init__(self, port, parent=None):
        super().__init__(parent)
        self.port = port
        self._stop = False
        self._reasm = {}     # sid -> Reassembler
        self._seq = {}       # sid -> last_seq
        self._st = {}        # sid -> dict(stats 计数)
        self._t0 = {}        # sid -> time
        self._last_bytes = {}  # sid -> 字节计数
        self._announced = set()
        # 最新完整帧缓存（供 MJPEG 桥/其它消费方读取）
        self.latest = {}     # sid -> jpeg bytes
        self.latest_seq = {}  # sid -> 序号(消费方判断新帧)
        self.latest_lock = threading.Lock()
        # 元数据: 设备名与稳定 key(sid 会漂移, key=VID:PID+序列号恒定)
        self.meta_name = {}  # sid -> name
        self.meta_key = {}   # sid -> key
        self.sid_by_key = {}  # key -> sid(最新)

    def get_latest_frame(self, sid):
        """线程安全读取某路最新完整帧; 返回 (seq, bytes) 或 None"""
        with self.latest_lock:
            seq = self.latest_seq.get(sid, -1)
            if seq < 0:
                return None
            return seq, self.latest[sid]

    def stop(self):
        self._stop = True

    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # 注意: 不用 SO_REUSEADDR —— Windows 上它允许同端口双 bind，
        # 新实例 bind "成功"但旧实例劫持全部包(极难排查)；端口被占应明确报错
        try:
            sock.bind(("0.0.0.0", self.port))
        except OSError as e:
            self.log_line.emit(t("port_bind_fail", port=self.port, err=e))
            return
        self.log_line.emit(t("listening_udp", port=self.port))

        while not self._stop:
            # 周期统计：独立于收帧（帧已停止的流也照常出统计）
            now = time.time()
            for sid in list(self._t0.keys()):
                st = self._st[sid]
                if st["frames"] > 0 and now - self._t0[sid] >= 1.0:
                    dt = now - self._t0[sid]
                    fps = st["frames"] / dt
                    mbps = st["bytes"] * 8 / dt / 1e6
                    self.stats_ready.emit(sid, t("stats_line", fps=f"{fps:.0f}",
                                                 mbps=f"{mbps:.2f}",
                                                 late=st["dup"], bad=st["bad"]))
                    st["frames"] = st["dup"] = st["bad"] = st["bytes"] = 0
                    self._t0[sid] = now
            try:
                data, _ = sock.recvfrom(receiver.PAYLOAD_MAX + receiver.HEADER_LEN + 16)
            except socket.timeout:
                continue
            except ConnectionResetError:
                # Windows UDP 坑: 收到 ICMP port-unreachable 后 recvfrom 抛 10054，
                # 属可忽略噪声，绝不能 break（否则接收线程静默死亡）
                continue
            except OSError:
                break
            if len(data) < receiver.HEADER_LEN or data[:2] != receiver.MAGIC:
                continue
            sid = data[2]
            seq, fidx, fcnt = struct.unpack_from("<IHH", data, 3)
            payload = data[receiver.HEADER_LEN:]
            if sid == 255:
                # 元数据控制包（sid=255, 无重组）: {"sid":N,"name":"...","kind":"...","key":"..."}
                try:
                    m = json.loads(payload.decode("utf-8", "replace"))
                    msid = int(m.get("sid", -1))
                    if msid >= 0:
                        if msid not in self._announced:
                            self._announced.add(msid)
                            self.new_stream.emit(msid)
                        self.meta_name[msid] = str(m.get("name", ""))
                        k = str(m.get("key", ""))
                        if k:
                            self.meta_key[msid] = k
                            self.sid_by_key[k] = msid
                        self.meta_ready.emit(msid, self.meta_name.get(msid, ""),
                                             str(m.get("kind", "")), k)
                except Exception:
                    pass
                continue
            if sid >= MAX_STREAMS:
                continue

            if sid not in self._reasm:
                self._reasm[sid] = receiver.Reassembler()
                self._seq[sid] = -1
                self._st[sid] = {"frames": 0, "dup": 0, "bad": 0, "bytes": 0}
                self._t0[sid] = time.time()
            if sid not in self._announced:
                self._announced.add(sid)
                self.new_stream.emit(sid)

            st = self._st[sid]
            last = self._seq[sid]
            if last >= 0:
                if seq < last and (last - seq) < 1000:
                    st["dup"] += 1
                    continue
                if seq > last + 1000:      # 发送端重启
                    self._reasm[sid].reset()
            jpeg = self._reasm[sid].push(seq, fidx, fcnt, payload)
            if jpeg is None:
                continue
            self._seq[sid] = seq
            st["frames"] += 1
            st["bytes"] += len(jpeg)
            with self.latest_lock:
                self.latest[sid] = jpeg
                self.latest_seq[sid] = seq
            self.frame_ready.emit(sid, jpeg)
        sock.close()
        self.log_line.emit(t("recv_thread_exit"))


# =====================================================================
# 等比画面视图: paintEvent 里按原图宽高比绘制(居中+黑边)，不拉伸变形
#   - 无逐帧 CPU 缩放(解码仍走原链路)，绘制开销与 setScaledContents 相当
# =====================================================================
class CameraView(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self._pix = None
        self._placeholder = t("waiting_frame")
        self.setMinimumSize(320, 200)
        self.setStyleSheet("background:#111; color:#888;")

    def set_image(self, img):
        """主线程调用: 存入 QImage 并触发重绘"""
        pm = QPixmap.fromImage(img)
        if pm.isNull():
            return
        self._pix = pm
        self._placeholder = None
        self.update()

    def clear_image(self):
        self._pix = None
        self._placeholder = t("waiting_frame")
        self.update()

    def has_image(self):
        return self._pix is not None and not self._pix.isNull()

    def set_placeholder(self, text):
        self._placeholder = text
        self.update()

    def paintEvent(self, ev):
        p = QPainter(self)
        p.fillRect(self.rect(), QColor("#111111"))
        if self._pix is not None and not self._pix.isNull():
            pr = self._pix.rect()
            wr = self.rect()
            if pr.width() > 0 and pr.height() > 0 and wr.width() > 0 and wr.height() > 0:
                # 等比缩放到窗口内(不拉伸变形)
                scale = min(wr.width() / pr.width(), wr.height() / pr.height())
                tw = max(1, int(pr.width() * scale))
                th = max(1, int(pr.height() * scale))
                tx = (wr.width() - tw) // 2
                ty = (wr.height() - th) // 2
                p.setRenderHint(QPainter.SmoothPixmapTransform, False)
                p.drawPixmap(tx, ty, tw, th, self._pix)
        elif self._placeholder:
            p.setPen(QColor("#888888"))
            p.drawText(self.rect(), Qt.AlignCenter, self._placeholder)
        p.end()


# =====================================================================
# 单路画面面板: 标题 + 画面 + 开关 + 统计
# =====================================================================
class CameraPanel(QFrame):
    def __init__(self, sid, display_fps=60, url=None, parent=None):
        super().__init__(parent)
        self.sid = sid
        self._enabled = True
        self._last_ui = 0.0
        # UI 显示限帧：默认 60fps；display_fps<=0 表示不限（全速显示）
        self._min_interval = (1.0 / display_fps) if display_fps and display_fps > 0 else 0.0

        self.setFrameStyle(QFrame.StyledPanel | QFrame.Raised)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(6, 6, 6, 6)

        # 标题行: Cam N [状态]  [开关按钮]
        head = QHBoxLayout()
        self.title = QLabel(t("cam_label", sid=sid))
        f = self.title.font()
        f.setBold(True)
        self.title.setFont(f)
        # 深底白字标题：用 QPalette(原生渲染路径)而非 QSS，
        # 抗 Windows 高对比/深色主题强制覆盖
        pal = self.title.palette()
        pal.setColor(QPalette.Window, QColor("#3a3a4a"))
        pal.setColor(QPalette.WindowText, QColor("#ffffff"))
        pal.setColor(QPalette.Text, QColor("#ffffff"))
        self.title.setPalette(pal)
        self.title.setAutoFillBackground(True)
        self.title.setStyleSheet("padding:3px 8px; font-weight:bold;")
        head.addWidget(self.title)
        head.addStretch(1)
        self.btn = QPushButton(t("btn_close_view"))
        self.btn.setFixedWidth(88)
        self.btn.clicked.connect(self.toggle)
        head.addWidget(self.btn)
        lay.addLayout(head)

        # 画面(自绘等比视图: 不拉伸变形，绘制开销同 setScaledContents)
        self.view = CameraView()
        lay.addWidget(self.view, 1)

        # 统计行
        self.stats = QLabel("")
        self.stats.setStyleSheet("color:#666;")
        lay.addWidget(self.stats)

        # 本地拉流地址（可复制）
        if url:
            url_row = QHBoxLayout()
            self.urlEdit = QLineEdit(url)
            self.urlEdit.setReadOnly(True)
            self.urlEdit.setCursorPosition(0)
            # 显式固定配色(浅底深字+边框)，避免受全局 palette/主题影响看不清
            self.urlEdit.setStyleSheet(
                "QLineEdit{font-size:11px; background:#ffffff; color:#111111;"
                " border:1px solid #b0b0b0; border-radius:3px; padding:2px 6px;}"
                "QLineEdit:focus{border:1px solid #ff7a1a;}")
            url_row.addWidget(self.urlEdit, 1)
            btnCopy = QPushButton(t("btn_copy"))
            btnCopy.setFixedWidth(52)
            btnCopy.setStyleSheet("color:#111111; background:#e8e8e8;")
            btnCopy.clicked.connect(lambda: self._copy_url())
            url_row.addWidget(btnCopy)
            lay.addLayout(url_row)

    def _copy_url(self):
        QApplication.clipboard().setText(self.urlEdit.text())

    def set_name(self, name):
        """元数据到达后把标题从 Cam N 更新为摄像头名称"""
        if name:
            self.title.setText(f"{name} · s{self.sid}")

    def set_url_key(self, key):
        """把拉流地址从 /cam/<sid> 换成 /cam/<稳定key>(插拔/换序后不变)"""
        if not (self.urlEdit and key):
            return
        try:
            import urllib.parse
            base = self.urlEdit.text().rsplit("/cam/", 1)[0]
            self.urlEdit.setText(base + "/cam/" + urllib.parse.quote(key, safe=""))
            self.urlEdit.setCursorPosition(0)
        except Exception:
            pass

    def toggle(self):
        self._enabled = not self._enabled
        self.btn.setText(t("btn_show_view") if not self._enabled else t("btn_close_view"))
        if not self._enabled:
            self.view.clear_image()
            self.view.set_placeholder(t("frame_closed"))
        else:
            self.view.set_placeholder(t("waiting_frame"))

    def is_enabled(self):
        return self._enabled

    def show_jpeg(self, jpeg):
        """主线程解码显示（按 display_fps 限帧；关闭时丢弃）"""
        if not self._enabled:
            return
        now = time.time()
        if self._min_interval > 0 and now - self._last_ui < self._min_interval:
            return
        img = QImage.fromData(jpeg, "JPG")
        if img.isNull():
            return
        self._last_ui = now
        self.view.set_image(img)

    def set_stats(self, text):
        self.stats.setText(text)

    def mark_active(self):
        self.title.setText(f"Cam {self.sid} ●")
        s = self.title.styleSheet()
        self.title.setStyleSheet("color:#1a7a1a;")


# =====================================================================
# 虚拟 UVC 摄像头输出: 把某路画面写进 OBS Virtual Camera 设备
#   - Baballonia 等软件切到 UVC 模式(本地摄像头)即可消费，绕开 HTTP/ffmpeg 缓冲
#   - 依赖: pip install pyvirtualcam; 设备为 OBS Virtual Camera(驱动已装)
# =====================================================================
class VCamThread(QThread):
    log_line = Signal(str)

    def __init__(self, worker, fps=60, sid=None, parent=None):
        """sid: None=自动选首路活跃流; 数字=固定某路; fps 最低 60"""
        super().__init__(parent)
        self.worker = worker
        self.fps = fps if fps and fps > 0 else 60
        self.sid = sid
        self._stop = False
        self._sent = 0

    def stop(self):
        self._stop = True

    def _pick_sid(self):
        if self.sid is not None:
            return self.sid
        ids = sorted(self.worker.latest_seq.keys())
        return ids[0] if ids else None

    def run(self):
        import pyvirtualcam
        cam = None
        seen_seq = -1
        w = h = 0
        self.log_line.emit("虚拟摄像头线程启动...")
        try:
            while not self._stop:
                sid = self._pick_sid()
                if sid is None:
                    time.sleep(0.2)
                    continue
                got = self.worker.get_latest_frame(sid)
                if got is None or got[0] == seen_seq:
                    time.sleep(0.005)
                    continue
                seen_seq = got[0]
                jpeg = got[1]
                frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
                if frame is None:
                    continue
                fh, fw = frame.shape[:2]
                if cam is None or (fh, fw) != (h, w):
                    if cam is not None:
                        try:
                            cam.close()
                        except Exception:
                            pass
                    w, h = fw, fh
                    cam = pyvirtualcam.Camera(width=w, height=h, fps=self.fps)
                    self.log_line.emit(
                        t("vcam_start", dev=cam.device, w=w, h=h,
                          fps=self.fps, sid=sid))
                # pyvirtualcam 期望 RGB；send 后必须 sleep_until_next_frame()
                # 对齐输出节奏(驱动端帧时序靠它维护，缺了会渐进卡顿)
                cam.send(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
                self._sent += 1
                cam.sleep_until_next_frame()
        except Exception as e:
            self.log_line.emit(t("vcam_error", err=e))
        finally:
            if cam is not None:
                try:
                    cam.close()
                except Exception:
                    pass
            self.log_line.emit("虚拟摄像头线程退出")


# =====================================================================
# MJPEG over HTTP 桥: 让桌面软件(OpenCV 系/IP 摄像头方式)能拉流
#   GET /          人类可读页面(每路 <img> 预览)
#   GET /list      JSON: {"streams": [0,1,...]}
#   GET /video     首路活跃流 (IP Webcam 兼容)
#   GET /cam/<sid> 指定路 MJPEG 流 (OpenCV VideoCapture 可直接打开)
# 输出格式逐字节模仿 OpenIris ESP-IDF 固件 components/StreamServer 的实现：
#   - Content-Type: multipart/x-mixed-replace;boundary=123456789000000000000987654321 (无空格)
#   - 响应头 X-Framerate: 60
#   - 每帧 part: Content-Type/Length + X-Timestamp: <sec>.<6位usec>
#   - 帧边界: \r\n--<boundary>\r\n + 头 + JPEG(无尾CRLF)，chunked 传输
# Baballonia 对这套格式(OpenIris WiFi 直推)实测流畅 60fps
OI_BOUNDARY = "123456789000000000000987654321"
OI_BOUNDARY_B = OI_BOUNDARY.encode()
OI_STREAM_BOUNDARY = b"\r\n--" + OI_BOUNDARY_B + b"\r\n"
class MjpegBridge:
    def __init__(self, worker, port=8080, max_fps=60, max_width=0):
        self.worker = worker
        self.port = port
        self.min_interval = 1.0 / max_fps if max_fps and max_fps > 0 else 0.0
        # 输出缩放：>0 时把帧等比缩到该宽度再发（Baballonia 类客户端吃小图才流畅）
        self.max_width = max_width
        self.server = None
        self.thread = None

    class _Handler(BaseHTTPRequestHandler):
        bridge = None  # 类属性注入

        def log_message(self, *a):
            pass  # 静默访问日志

        def _stream_ids(self):
            return sorted(self.bridge.worker.latest_seq.keys())

        def _first_active_sid(self):
            """最小活跃流 sid（有最新帧的），无则 None"""
            ids = [s for s in self._stream_ids()
                   if self.bridge.worker.get_latest_frame(s) is not None]
            return ids[0] if ids else None

        def _resize_jpeg(self, jpeg, max_width):
            """等比缩小 JPEG 到 max_width 宽（大图客户端解码慢，缩小喂更流畅）"""
            try:
                frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
                if frame is None:
                    return jpeg
                h, w = frame.shape[:2]
                if w <= max_width:
                    return jpeg
                nh = int(h * max_width / w)
                if nh < 1:
                    nh = 1
                small = cv2.resize(frame, (max_width, nh), interpolation=cv2.INTER_AREA)
                ok, buf = cv2.imencode(".jpg", small, [cv2.IMWRITE_JPEG_QUALITY, 75])
                return buf.tobytes() if ok else jpeg
            except Exception:
                return jpeg

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/":
                # 根路径：有活跃流则跳转 /video(兼容只填裸 IP 的客户端如 Baballonia)；
                # 无流则给出提示页
                sid = self._first_active_sid()
                if sid is None:
                    self._send_index()
                else:
                    self.send_response(302)
                    self.send_header("Location", "/video")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
            elif path == "/video":
                # IP Webcam 兼容流路径：输出"首路活跃流"的 MJPEG
                sid = self._first_active_sid()
                if sid is None:
                    self.send_error(404, "no stream")
                    return
                self._stream_mjpeg(sid)
            elif path == "/list":
                self._send_json()
            elif path.startswith("/cam/"):
                seg = path[len("/cam/"):]
                try:
                    sid = int(seg)
                except ValueError:
                    # 按稳定设备 key 路由: /cam/<url编码key>
                    try:
                        import urllib.parse as _up
                        key = _up.unquote(seg)
                        sid = self.bridge.worker.sid_by_key.get(key, -1)
                    except Exception:
                        sid = -1
                if sid < 0:
                    self.send_error(404)
                    return
                self._stream_mjpeg(sid)
            elif path.startswith("/latest/"):
                try:
                    sid = int(path[len("/latest/"):])
                except (ValueError, IndexError):
                    self.send_error(404)
                    return
                self._send_single_jpeg(sid)
            else:
                self.send_error(404)

        def _send_index(self):
            import urllib.parse as _up
            sids = self._stream_ids()
            rows = ""
            for s in sids:
                k = self.bridge.worker.meta_key.get(s)
                addr = f"/cam/{_up.quote(k, safe='')}" if k else f"/cam/{s}"
                rows += (f"<h3>{s}: {self.bridge.worker.meta_name.get(s, '')}</h3>"
                         f"<img src='/cam/{s}' width='480'>"
                         f"<p>推流地址(稳定): <code>http://"
                         f"{self.server.server_address[0]}:{self.bridge.port}{addr}</code></p>")
            if not rows:
                rows = f"<p>{t('no_stream')}</p>"
            html = ("<html><head><title>VRCReceiver MJPEG</title></head>"
                    f"<body><h2>VRCReceiver · MJPEG 桥</h2>{rows}</body></html>")
            body = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _send_json(self):
            body = json.dumps({"streams": self._stream_ids()}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _send_single_jpeg(self, sid):
            got = self.bridge.worker.get_latest_frame(sid)
            if got is None:
                self.send_error(404, "no stream")
                return
            _, jpeg = got
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(jpeg)))
            self.end_headers()
            self.wfile.write(jpeg)

        def _stream_mjpeg(self, sid):
            """OpenIris StreamServer 同款 chunked MJPEG 流"""
            if self.bridge.worker.get_latest_frame(sid) is None:
                self.send_error(404, "no stream")
                return
            self.protocol_version = "HTTP/1.1"
            self.send_response(200)
            # 与 OpenIris 完全一致：boundary= 后无空格
            self.send_header("Content-Type",
                             f"multipart/x-mixed-replace;boundary={OI_BOUNDARY}")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("X-Framerate", "60")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "keep-alive")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            seen_seq = -1
            interval = self.bridge.min_interval or 0.0
            next_send = 0.0  # 累积目标发送时刻(精确对齐，消除 5ms 轮询抖动)
            try:
                while True:
                    now = time.time()
                    if now >= next_send:
                        got = self.bridge.worker.get_latest_frame(sid)
                        if got is not None and got[0] != seen_seq:
                            _, jpeg = got
                            mw = self.bridge.max_width
                            if mw and mw > 0:
                                jpeg = self._resize_jpeg(jpeg, mw)
                            # 与 OpenIris StreamServer 布局一致:
                            # \r\n--boundary\r\n + 头(X-Timestamp) + JPEG(无尾CRLF)
                            sec = int(now)
                            usec = int((now - sec) * 1_000_000)
                            part = (b"Content-Type: image/jpeg\r\n"
                                    + b"Content-Length: " + str(len(jpeg)).encode()
                                    + b"\r\nX-Timestamp: " + str(sec).encode() + b"."
                                    + ("%06d" % usec).encode() + b"\r\n\r\n")
                            body = OI_STREAM_BOUNDARY + part + jpeg
                            chunk = (b"%x\r\n" % len(body)) + body + b"\r\n"
                            self.wfile.write(chunk)
                            self.wfile.flush()
                            seen_seq = got[0]
                            # 下一帧目标时刻 = 本帧 + interval(累积制，漂移自动修正)
                            if interval > 0:
                                next_send += interval
                                if next_send < now:
                                    next_send = now + interval  # 落后太多则重锚
                            else:
                                next_send = now
                            continue
                        elif interval <= 0:
                            time.sleep(0.005)
                            continue
                    # 睡到下一目标点(分片 10ms，避免长睡阻塞新帧响应)
                    wait = next_send - time.time()
                    if wait > 0:
                        time.sleep(min(wait, 0.010))
                    else:
                        time.sleep(0.002)
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            except Exception:
                pass
            finally:
                try:
                    self.wfile.write(b"0\r\n\r\n")
                    self.wfile.flush()
                except Exception:
                    pass

    def start(self):
        class Handler(self._Handler):
            pass
        Handler.bridge = self
        try:
            self.server = ThreadingHTTPServer(("0.0.0.0", self.port), Handler)
        except OSError as e:
            print(t("port_bind_fail", port=self.port, err=e))
            return False
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       daemon=True, name="mjpeg-bridge")
        self.thread.start()
        print(t("bridge_ready", port=self.port))
        return True

    def stop(self):
        if self.server is not None:
            try:
                self.server.shutdown()
                self.server.server_close()
            except Exception:
                pass


# =====================================================================
# 主窗口
# =====================================================================
class MainWindow(QMainWindow):
    def __init__(self, port, display_fps=60, mjpeg_port=8080, mjpeg_fps=60,
                 vcam=None, vcam_fps=60, mjpeg_max_width=0):
        super().__init__()
        self.setWindowTitle(t("win_title"))
        self.resize(1080, 720)
        self.display_fps = display_fps
        self.mjpeg_port = mjpeg_port
        self.bridge = None
        self.vcam = None

        self.panels = {}     # sid -> CameraPanel
        self.worker = None

        # 托盘（系统通知提示用）
        self.tray = QSystemTrayIcon(self)
        self.tray.setIcon(self.style().standardIcon(QStyle.SP_DriveNetIcon))
        menu = QMenu()
        act_quit = QAction(t("tray_quit"), self)
        act_quit.triggered.connect(self.close)
        menu.addAction(act_quit)
        self.tray.setContextMenu(menu)
        self.tray.show()

        # 中央滚动区 + 网格
        self.grid = QGridLayout()
        self.grid.setSpacing(8)
        container = QWidget()
        container.setLayout(self.grid)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(container)
        self.setCentralWidget(scroll)

        # 状态栏
        self.statusBar().showMessage(t("starting"))

        # 启动接收线程
        self.worker = StreamWorker(port)
        self.worker.frame_ready.connect(self.on_frame)
        self.worker.new_stream.connect(self.on_new_stream)
        self.worker.meta_ready.connect(self.on_meta)
        self.worker.stats_ready.connect(self.on_stats)
        self.worker.log_line.connect(lambda s: self.statusBar().showMessage(s, 6000))
        self.worker.start()

        # 虚拟 UVC 摄像头输出（可选：--vcam auto/数字 开启；默认关闭避免占用设备）
        if vcam is not None:
            vcam_sid = None if vcam == "auto" else vcam
            self.vcam = VCamThread(self.worker, fps=max(60, vcam_fps), sid=vcam_sid)
            self.vcam.log_line.connect(lambda s: self.statusBar().showMessage(s, 6000))
            self.vcam.start()

        # beacon 应答（自动搜索）守护线程
        import threading
        threading.Thread(target=receiver.beacon_loop, daemon=True).start()

        # MJPEG over HTTP 桥（供桌面软件以 IP 摄像头方式拉流）
        if mjpeg_port and mjpeg_port > 0:
            self.bridge = MjpegBridge(self.worker, port=mjpeg_port,
                                      max_fps=mjpeg_fps,
                                      max_width=mjpeg_max_width)
            self.bridge.start()

        self.statusBar().showMessage(t("status_listen", port=port,
                                       ip=receiver.local_ip(), mj=mjpeg_port))

    # ---------------- 槽 ----------------
    def _create_panel(self, sid):
        url = f"http://127.0.0.1:{self.mjpeg_port}/cam/{sid}" if self.mjpeg_port else None
        panel = CameraPanel(sid, self.display_fps, url)
        self.panels[sid] = panel
        col = sid % 2
        row = sid // 2
        self.grid.addWidget(panel, row, col)
        return panel

    def on_new_stream(self, sid):
        if sid in self.panels:
            return
        panel = self._create_panel(sid)
        # 系统通知提示
        self.tray.showMessage(
            t("new_stream_title"),
            t("new_stream_body", sid=sid),
            QSystemTrayIcon.Information, 4000)
        self.statusBar().showMessage(t("new_stream_status", sid=sid), 5000)
        panel.mark_active()

    def on_meta(self, sid, name, kind, key):
        panel = self.panels.get(sid)
        if panel is None:
            panel = self._create_panel(sid)
            panel.mark_active()
        if name:
            panel.set_name(name)
        if key:
            panel.set_url_key(key)

    def on_frame(self, sid, jpeg):
        p = self.panels.get(sid)
        if p is not None:
            p.show_jpeg(jpeg)

    def on_stats(self, sid, text):
        p = self.panels.get(sid)
        if p is not None:
            p.set_stats(text)

    def closeEvent(self, ev):
        if self.bridge is not None:
            self.bridge.stop()
        if getattr(self, "vcam", None) is not None:
            self.vcam.stop()
            self.vcam.wait(2000)
        if self.worker is not None:
            self.worker.stop()
            self.worker.wait(2000)
        super().closeEvent(ev)


def _show_help_dialog(ap):
    """打包为 GUI exe 时用弹窗展示命令行参数(无控制台可读)"""
    try:
        from PySide6.QtWidgets import QApplication, QMessageBox
        _app = QApplication.instance() or QApplication([])
        box = QMessageBox()
        box.setWindowTitle("VRCReceiver — 命令行参数")
        box.setText("可用启动参数（在 PowerShell 中运行本程序时使用）")
        box.setDetailedText(ap.format_help())
        box.setInformativeText(
            "默认双击运行即可，无需任何参数。\n"
            "例：VRCReceiver.exe --lang zh --mjpeg-port 8080")
        box.exec()
    except Exception:
        # 兜底：无 GUI 环境时打印(仅在有控制台时可见)
        print(ap.format_help())


def main():
    ap = argparse.ArgumentParser(description="VRCReceiver — VR 外接摄像头多路接收端")
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--lang", default=None,
                    help="界面语言: zh/en/ja/ko/ru（默认跟随系统，其它语言显示英语）")
    ap.add_argument("--display-fps", type=int, default=60,
                    help="画面显示帧率上限（0=不限制，按流真实帧率显示）")
    ap.add_argument("--mjpeg-port", type=int, default=8080,
                    help="MJPEG over HTTP 桥端口（0=禁用），桌面软件拉流用")
    ap.add_argument("--mjpeg-fps", type=int, default=60,
                    help="MJPEG 桥输出帧率（默认 60=OpenIris WiFi 直推同水平，已实测流畅）")
    ap.add_argument("--mjpeg-max-width", type=int, default=0,
                    help="MJPEG 桥输出等比缩放宽度(0=原尺寸)。Baballonia 卡时先试 320："
                         "大图(640x480)客户端解码慢，缩成小图(如 XIAO WiFi 流的 240x240 特征)才流畅")
    ap.add_argument("--vcam", default="off",
                    help="虚拟 UVC 摄像头输出: auto=首路活跃流 / 数字=指定路 / off=禁用(默认)")
    ap.add_argument("--vcam-fps", type=int, default=60,
                    help="虚拟摄像头输出帧率（默认 60）")
    # 打包成 exe(无控制台)时 --help 无文字输出，用弹窗展示参数说明
    args, _unknown = ap.parse_known_args()
    if _unknown and any(a in ("-h", "--help") for a in _unknown + sys.argv[1:]):
        _show_help_dialog(ap)
        return

    # 语言：显式 --lang 优先，否则跟随系统，不支持的语言回退英语
    i18n.init(args.lang)

    app = QApplication([])
    app.setApplicationName("VRCReceiver")
    vcam = None if args.vcam == "off" else (
        "auto" if args.vcam == "auto" else int(args.vcam))
    win = MainWindow(args.port, args.display_fps, args.mjpeg_port,
                     args.mjpeg_fps, vcam, args.vcam_fps, args.mjpeg_max_width)
    win.show()
    app.exec()


if __name__ == "__main__":
    main()
