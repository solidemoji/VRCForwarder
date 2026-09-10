package com.example.uvcforwarder;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * UVC 会话核心：枚举视频流接口 -> 标准 probe/commit 握手 -> bulk 收流 -> 组装 JPEG 帧。
 *
 * 相对旧版的修复：
 *  1. 只筛选 bInterfaceClass=0x0E(Video) 且 subclass=2(Streaming) 的接口，
 *     绝不碰 CDC 串口接口（旧版误把 CDC 的 bulk-IN 端点当视频端点，握手全被 STALL）。
 *  2. 去掉 fps/frameIndex 穷举"爆破"，直接使用已核实的固件参数并保留标准协商 fallback。
 *  3. payload 解析与 TinyUSB 实测一致：每次 bulkTransfer 返回一个 ≤64B payload
 *     （2B 头 + 数据，EOF=byte1 bit1），累积到 EOF 即一帧完成。
 */
public class UvcStreamer {

    private static final String TAG = "UVC_STREAMER";

    public interface Listener {
        /** 完整 JPEG 帧就绪（240x240 MJPEG）。调用方应快速处理/入队，勿阻塞。 */
        void onFrame(byte[] jpeg);
        /** 状态文本（用于 UI） */
        void onState(String text);
        /** 每秒统计 */
        void onStats(float fps, int frameBytes);
    }

    private final UsbManager usbManager;
    private final Listener listener;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface vsInterface;
    private UsbEndpoint bulkIn;
    private Thread readThread;
    private volatile boolean running = false;
    private long interval100ns = UvcProto.FRAME_INTERVAL_30;

    // 统计
    private volatile float curFps = 0;
    private volatile int curFrameBytes = 0;
    private long statFrames = 0;
    private long statBytes = 0;
    private long statStart = 0;

    public UvcStreamer(UsbManager usbManager, Listener listener) {
        this.usbManager = usbManager;
        this.listener = listener;
    }

    /** USB 视频设备的传输类型：自研 bulk 直读 / 标准 ISO(需 saki libuvc) / 无 UVC */
    public enum UvcKind { BULK, ISO, NONE }

    /** 从已枚举设备中挑选"视频流接口 + bulk-IN 端点"。找不到返回 null。 */
    public static UsbInterface findStreamingInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            // UVC 视频流接口
            if (itf.getInterfaceClass() == UvcProto.USB_CLASS_VIDEO) {
                for (int j = 0; j < itf.getEndpointCount(); j++) {
                    UsbEndpoint ep = itf.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        return itf; // 该接口上已有输入 bulk 端点，直接可用
                    }
                }
            }
        }
        return null;
    }

    /**
     * 分类设备：按视频流接口的 IN 端点类型判定。
     * bulk-IN → BULK(XIAO/OpenIris 类)；isochronous-IN → ISO(标准免驱 UVC)；
     * 只有 VC 无 VS 或无线端点 → NONE。
     */
    public static UvcKind classify(UsbDevice d) {
        boolean hasVideo = false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() != UvcProto.USB_CLASS_VIDEO) continue;
            hasVideo = true;
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                if (ep.getDirection() != UsbConstants.USB_DIR_IN) continue;
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) return UvcKind.BULK;
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) return UvcKind.ISO;
            }
        }
        return UvcKind.NONE;
    }

    /**
     * 读 UVC 描述符里 VideoControl 接口的 iInterface 名称（UVC 摄像头名）。
     * 优先 getInterfaceName()(系统已解析描述符字符串)；取不到则手动解析
     * VC 接口附加描述符里的 Camera Terminal(iTerminal 字符串索引)再查字符串描述符。
     * 失败返回 null。
     */
    public static String uvcInterfaceName(UsbDevice d, UsbDeviceConnection conn) {
        // 路线1: 系统 API(部分 ROM 已填充接口名字符串)
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() != UvcProto.USB_CLASS_VIDEO) continue;
            if (itf.getInterfaceSubclass() != 1) continue;  // 1 = VideoControl
            try {
                String n = itf.getName();
                if (n != null && !n.trim().isEmpty()) return n.trim();
            } catch (Exception ignored) {}
        }
        if (conn == null) return null;
        // 路线2: 手动解析 VC 接口附加描述符 -> Camera Terminal 的 iTerminal
        try {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface itf = d.getInterface(i);
                if (itf.getInterfaceClass() != UvcProto.USB_CLASS_VIDEO) continue;
                if (itf.getInterfaceSubclass() != 1) continue;  // VideoControl
                byte[] buf = new byte[512];
                // 取配置描述符(gIndex=i)后按偏移解析
                int n = conn.controlTransfer(0x80, 6 /*GET_DESCRIPTOR*/,
                        (2 << 8) | 0 /*CONFIG*/ | (i << 8), 0, buf, buf.length, 1000);
                if (n < 4) continue;
                for (int p = 0; p + 3 < n; ) {
                    int blen = buf[p] & 0xFF;
                    if (blen < 2) break;
                    int btype = buf[p + 1] & 0xFF;
                    // CS_INTERFACE(0x24) 子类型 0x02 = Camera Terminal, iTerminal 在 +2
                    if (btype == 0x24 && (buf[p + 2] & 0xFF) == 0x02) {
                        int iTerminal = buf[p + 3] & 0xFF;
                        String nm = readStringDescriptor(conn, iTerminal);
                        if (nm != null && !nm.trim().isEmpty()) return nm.trim();
                    }
                    p += blen;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 读 USB 字符串描述符(index)，返回 UTF-8 字符串；失败 null */
    private static String readStringDescriptor(UsbDeviceConnection conn, int index) {
        if (index <= 0) return null;
        try {
            byte[] head = new byte[2];
            int r = conn.controlTransfer(0x80, 6, (3 << 8) | index, 0x0409, head, 2, 1000);
            if (r < 2) return null;
            int len = head[0] & 0xFF;
            if (len < 2) return null;
            byte[] buf = new byte[len];
            r = conn.controlTransfer(0x80, 6, (3 << 8) | index, 0x0409, buf, len, 1000);
            if (r < 2) return null;
            // UTF-16LE -> String(跳过头2字节)
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i + 1 < len && i + 1 < r; i += 2) {
                sb.append((char) ((buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8)));
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 稳定身份 key：类型 + VID:PID + 序列号（无序列号则空串）——槽位排序用，保证序号不漂移 */
    public static String deviceKey(UsbDevice d) {
        int kind = classify(d).ordinal();
        String id = String.format(java.util.Locale.US, "%04X:%04X",
                d.getVendorId(), d.getProductId());
        String ser = d.getSerialNumber();
        return kind + ":" + id + ":" + (ser != null ? ser : "");
    }

    /** 人类可读名称：UVC 接口名 > USB 产品名 > VID:PID，+ 序列号尾(区分左右眼同型号) */
    public static String deviceDisplayName(UsbDevice d) {
        return deviceDisplayName(d, null);
    }

    /** conn 非空时可读 UVC 描述符里的摄像头名(部分设备产品名笼统, 如统一叫 "XIAO") */
    public static String deviceDisplayName(UsbDevice d, UsbDeviceConnection conn) {
        String uvc = (conn != null) ? uvcInterfaceName(d, conn) : null;
        String p = uvc;
        if (p == null || p.trim().isEmpty()) p = d.getProductName();
        String name = (p != null && !p.trim().isEmpty())
                ? p.trim()
                : String.format(java.util.Locale.US, "%04X:%04X",
                        d.getVendorId(), d.getProductId());
        String s = d.getSerialNumber();
        if (s != null && !s.isEmpty()) {
            name += "#" + (s.length() > 6 ? s.substring(s.length() - 6) : s);
        }
        return name;
    }

    /** JSON 字符串转义（名称里可能有引号/反斜杠/控制符） */
    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    public UsbDevice getDevice() { return device; }

    /** 启动：要求调用方已持有该设备的 USB 权限 */
    public synchronized void start(UsbDevice dev, long interval100ns) throws IOException {
        stop();
        this.device = dev;
        this.interval100ns = interval100ns;

        vsInterface = findStreamingInterface(dev);
        if (vsInterface == null) {
            throw new IOException("未找到 UVC 视频流接口(Class 0x0E)。"
                    + "设备接口数=" + dev.getInterfaceCount() + "，请确认固件已启用 UVC 模式");
        }
        Log.i(TAG, "选中 VS 接口 id=" + vsInterface.getId()
                + " class=" + vsInterface.getInterfaceClass()
                + " subclass=" + vsInterface.getInterfaceSubclass());

        connection = usbManager.openDevice(dev);
        if (connection == null) {
            throw new IOException("openDevice 失败：USB 权限可能未授予或设备被占用");
        }

        // 只 claim 视频接口（含 VC 若有）。CDC 接口让系统/其他应用使用，互不干扰。
        boolean claimed = connection.claimInterface(vsInterface, true);
        if (!claimed) {
            // 部分内核在 claim 前需先 setInterface(alt 0)
            connection.setInterface(vsInterface);
            claimed = connection.claimInterface(vsInterface, true);
        }
        if (!claimed) {
            throw new IOException("claimInterface 失败（接口被占用？）");
        }

        for (int j = 0; j < vsInterface.getEndpointCount(); j++) {
            UsbEndpoint ep = vsInterface.getEndpoint(j);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                bulkIn = ep;
                break;
            }
        }
        if (bulkIn == null) {
            throw new IOException("VS 接口上没有 bulk-IN 端点（该固件应是 bulk 模式）");
        }

        // ---- 握手：SET_CUR(PROBE) -> SET_CUR(COMMIT) ----
        byte[] probe = buildProbe26(interval100ns);
        int vsId = vsInterface.getId();
        notify("正在与摄像头协商视频参数(" + (10000000 / interval100ns) + "fps)...");

        int s1 = connection.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                UvcProto.SEL_VS_PROBE_CONTROL, vsId, probe, UvcProto.PROBE_LEN, 500);
        int s2 = connection.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                UvcProto.SEL_VS_COMMIT_CONTROL, vsId, probe, UvcProto.PROBE_LEN, 1000);
        Log.i(TAG, "probe ret=" + s1 + ", commit ret=" + s2);

        if (s2 != UvcProto.PROBE_LEN) {
            // fallback：标准协商流程——SET probe -> GET probe(读回固件认可参数) -> 用读回值 COMMIT
            Log.w(TAG, "直接 COMMIT 失败(" + s2 + ")，尝试标准协商流程");
            byte[] back = new byte[34]; // UVC 1.5 版结构长度
            int g = connection.controlTransfer(UvcProto.BMREQ_DEV_TO_HOST, UvcProto.REQ_GET_CUR,
                    UvcProto.SEL_VS_PROBE_CONTROL, vsId, back, back.length, 500);
            if (g > 0) {
                int s3 = connection.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                        UvcProto.SEL_VS_COMMIT_CONTROL, vsId, back, g, 1000);
                Log.i(TAG, "协商后 commit(" + g + "B) ret=" + s3);
                if (s3 == g) {
                    notify("握手成功（协商模式），开始接收视频流");
                } else {
                    throw new IOException("COMMIT 被固件拒绝：s1=" + s1 + " s2=" + s2 + " s3=" + s3
                            + "。请确认固件处于 UVC 模式且参数匹配");
                }
            } else {
                throw new IOException("probe/commit 均失败：s1=" + s1 + " s2=" + s2
                        + " GET=" + g + "。可能固件未运行或接口选择错误");
            }
        } else {
            notify("握手成功，开始接收视频流");
        }

        running = true;
        readThread = new Thread(this::readLoop, "uvc-reader");
        readThread.start();
    }

    /** 构造 26 字节 VS_PROBE_CONTROL（UVC 1.0/1.1 布局，固件兼容） */
    private static byte[] buildProbe26(long interval100ns) {
        byte[] p = new byte[UvcProto.PROBE_LEN];
        p[2] = (byte) UvcProto.FMT_INDEX;
        p[3] = (byte) UvcProto.FRAME_INDEX;
        // dwFrameInterval (LE, 100ns 单位)
        p[4] = (byte) (interval100ns & 0xFF);
        p[5] = (byte) ((interval100ns >> 8) & 0xFF);
        p[6] = (byte) ((interval100ns >> 16) & 0xFF);
        p[7] = (byte) ((interval100ns >> 24) & 0xFF);
        // dwMaxVideoFrameSize (LE)
        int mf = UvcProto.MAX_VIDEO_FRAME_SIZE;
        p[18] = (byte) (mf & 0xFF);
        p[19] = (byte) ((mf >> 8) & 0xFF);
        p[20] = (byte) ((mf >> 16) & 0xFF);
        p[21] = (byte) ((mf >> 24) & 0xFF);
        // dwMaxPayloadTransferSize (LE)——固件会自行 clamp 到 64
        int mp = UvcProto.MAX_PAYLOAD_SIZE;
        p[22] = (byte) (mp & 0xFF);
        p[23] = (byte) ((mp >> 8) & 0xFF);
        p[24] = (byte) ((mp >> 16) & 0xFF);
        p[25] = (byte) ((mp >> 24) & 0xFF);
        return p;
    }

    private void readLoop() {
        byte[] buf = new byte[16384];
        ByteArrayOutputStream frame = new ByteArrayOutputStream(64 * 1024);
        int idleCount = 0;
        long lastIdleWarn = 0;
        statStart = System.currentTimeMillis();

        while (running) {
            int n;
            try {
                n = connection.bulkTransfer(bulkIn, buf, buf.length, 200);
            } catch (Exception e) {
                Log.e(TAG, "bulkTransfer 异常: " + e);
                break;
            }
            if (n < 0) { // 超时
                idleCount++;
                long now = System.currentTimeMillis();
                if (idleCount % 50 == 0 && now - lastIdleWarn > 3000) {
                    lastIdleWarn = now;
                    notify("正在等待图像数据... (无数据 " + (idleCount * 200 / 1000) + "s)");
                }
                continue;
            }
            if (n == 0) continue;
            idleCount = 0;

            // ===== UVC payload 流解析 =====
            // 关键修正：FS bulk 的 64B 是整包(非短包)，Android URB 会连续吸收多个 payload，
            // 一次 bulkTransfer 返回几十个 payload 拼接(最多 16KB)，直到帧尾短包(EOF 块<64B)才终止。
            // 因此必须按 payload 逐个切分，而不是把一次 read 当一个 payload。
            // payload 总长 = 64B (2B 头 + 62B 数据) —— 固件 FS bulk 下 clamp 到 ep 缓冲 64；
            // 帧尾 EOF 块是短包(<64B)且必然位于 read 末尾（短包终止 URB）。
            int i = 0;
            while (i + 2 <= n) {
                int hl = buf[i] & 0xFF;
                if (hl < 2 || hl > 64) {
                    // 头长度异常：字节流错位。若当前帧还没数据且这里像 JPEG 头则按裸流兜底
                    if (frame.size() == 0 && i + 1 < n
                            && (buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xFF) == 0xD8) {
                        Log.w(TAG, "payload 头异常但发现 FF D8，按裸 JPEG 处理 i=" + i);
                        frame.write(buf, i, n - i);
                        i = n;
                    } else {
                        Log.w(TAG, "payload 头异常 hl=" + hl + " @i=" + i + " n=" + n + "，跳过 1B");
                        i++;
                    }
                    continue;
                }
                boolean eof = (buf[i + 1] & 0x02) != 0; // TinyUSB: EndOfFrame=bit1
                if (eof) {
                    if (i + 64 <= n) {
                        // EOF 块恰为 64B 整包(帧数据是 62 的倍数)：非短包，URB 未终止，
                        // 其后可能紧跟下一帧数据 → 消费 62B、完成本帧、继续解析
                        if (64 - hl > 0) frame.write(buf, i + hl, 64 - hl);
                        i += 64;
                        onFrameComplete(frame);
                        continue;
                    } else {
                        // EOF 短包(<64B)：短包终止本次 read，本块数据 = 剩余全部
                        if (n - i - hl > 0) frame.write(buf, i + hl, n - i - hl);
                        i = n;
                        onFrameComplete(frame);
                        break;
                    }
                } else {
                    // 普通 payload：整 64B（2B 头 + 62B 数据）
                    if (i + 64 > n) {
                        // 理论不发生（64B 整包必被 URB 完整吸收）；若发生则本帧已残，丢弃重启
                        Log.w(TAG, "payload 在 read 尾部截断 i=" + i + " n=" + n
                                + " frame已收=" + frame.size() + "，丢弃当前帧");
                        frame.reset();
                        break;
                    }
                    frame.write(buf, i + hl, 64 - hl);
                    i += 64;
                }
            }
            if (frame.size() > UvcProto.MAX_FRAME_BYTES) {
                Log.w(TAG, "单帧超过上限，重置");
                frame.reset();
            }
            // 每秒统计
            long now = System.currentTimeMillis();
            if (now - statStart >= 1000) {
                curFps = statFrames * 1000f / (now - statStart);
                if (listener != null) listener.onStats(curFps, (int) (statBytes / Math.max(1, statFrames)));
                statFrames = 0;
                statBytes = 0;
                statStart = now;
            }
        }

        // 线程退出清理
        try {
            if (connection != null && vsInterface != null) {
                connection.releaseInterface(vsInterface);
            }
        } catch (Exception ignored) {}
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
        if (running) { // 非主动停止导致的退出
            notify("视频流已中断");
        }
        Log.i(TAG, "read thread exited");
    }

    /** 帧收齐后：校验 JPEG 魔数、更新统计、回调上层。会清空传入的缓冲。 */
    private void onFrameComplete(ByteArrayOutputStream frame) {
        byte[] jpeg = frame.toByteArray();
        frame.reset();
        if (jpeg.length >= 2 && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8
                && jpeg.length < UvcProto.MAX_FRAME_BYTES) {
            curFrameBytes = jpeg.length;
            statBytes += jpeg.length;
            statFrames++;
            if (listener != null) listener.onFrame(jpeg);
        } else {
            Log.w(TAG, "帧校验失败丢弃 len=" + jpeg.length);
        }
    }

    public synchronized void stop() {
        running = false;
        if (readThread != null) {
            try { readThread.join(500); } catch (InterruptedException ignored) {}
            readThread = null;
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
    }

    private void notify(String text) {
        Log.i(TAG, text);
        if (listener != null) listener.onState(text);
    }

    public float getCurFps() { return curFps; }
    public int getCurFrameBytes() { return curFrameBytes; }
}
