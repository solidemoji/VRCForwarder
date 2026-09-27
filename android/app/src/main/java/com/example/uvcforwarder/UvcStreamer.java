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
    /** probe/commit 控制传输使用的 wIndex（接口号）。正常=VS 接口 id；兜底推测=VC+1 */
    private int probeWIndex = -1;
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
     *
     * ★ Pico 4 Pro 兼容（实测根因，2026-09）：
     * 部分一体机系统（Pico 4 Pro / Android 10 / PICO OS 5.13.7）对**复合 USB 设备的
     * 接口枚举不完整** —— XIAO 这类 [CDC + CDC-Data + UVC-VC + UVC-VS] 四接口设备，
     * 系统只暴露出前三个（含 UVC VideoControl 14/1），**缺失 VideoStreaming 接口(14/2)**
     * 及其 bulk-IN 端点。此时端点循环一次都不进 → 被误判为 NONE → 设备不进列表。
     *
     * 因此增加两级兜底：
     *   ① 有 class=14 接口但找不到任何 IN 端点 → 用已知 VID/PID 表判定（BULK），
     *      让设备至少能进列表/可被打开（能否取流由后续 tryStartSession 决定）。
     *   ② 仍有 class=14 但 VID/PID 不认识的 → 归为 ISO（标准免驱设备的常见形态），
     *      由 saki 路径尝试；失败会在会话启动阶段报错，不影响其它设备。
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
        if (!hasVideo) return UvcKind.NONE;

        // ---- 兜底：有 UVC 视频接口但系统没暴露流接口端点 ----
        if (isKnownBulkUvc(d)) return UvcKind.BULK;   // XIAO / OpenIris 等自研 bulk 固件
        return UvcKind.ISO;                            // 未知设备按免驱处理（会尝试 saki）
    }

    /**
     * 已知的自研 bulk-UVC 设备（VID:PID 表）。
     * 用于「系统接口枚举不完整」时仍能正确分类（见 classify 注释）。
     *  3534:32768 = 0x303A:0x8000  Espressif XIAO ESP32-S3 UVC 模式（OpenIris/Cymple 固件）
     *  3534:4097  = 0x303A:0x1001  Espressif USB JTAG/serial（两段式枚举的中间态，非摄像头）
     */
    private static final int[][] KNOWN_BULK_VID_PID = {
            {0x303A, 0x8000},   // XIAO ESP32-S3 UVC
            {0x303A, 0x8001},   // XIAO ESP32-S3 UVC（备用 PID）
            {0x303A, 0x4001},   // ESP32-S2/S3 摄像头类固件
    };

    /** 该设备是否在已知 bulk-UVC 白名单内 */
    public static boolean isKnownBulkUvc(UsbDevice d) {
        for (int[] vp : KNOWN_BULK_VID_PID) {
            if (d.getVendorId() == vp[0] && d.getProductId() == vp[1]) return true;
        }
        return false;
    }

    /**
     * 找 UVC VideoControl 接口（class=14, subclass=1）。用于系统未暴露 VS 接口时的兜底。
     * 找不到就退化为「第一个 class=14 接口」。
     */
    public static UsbInterface findVideoControlInterface(UsbDevice d) {
        UsbInterface first14 = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() != UvcProto.USB_CLASS_VIDEO) continue;
            if (first14 == null) first14 = itf;
            if (itf.getInterfaceSubclass() == UvcProto.VC_SUBCLASS) return itf;
        }
        return first14;
    }

    /**
     * 在设备任意接口上找 bulk-IN 端点（系统未暴露 VS 接口时的兜底）。
     *
     * ★ 两级优先（Pico 4 Pro 实验，2026-09）：
     *   ① 优先非 CDC 接口（class != 2/10）的 bulk-IN —— 正常的视频流端点；
     *   ② 若只有 CDC 接口带 bulk-IN（实测 Pico 4 Pro/PICO OS 5.13.7 的系统
     *      遗漏了 UVC VideoStreaming 接口，唯一 bulk-IN 落在 CDC Data 接口上，
     *      如 XIAO 的 0x82），则**退一步尝试它**。
     *
     *   原理：XIAO 的 TinyUSB 复合设备在多接口场景下，若系统把 VS 接口"折叠"掉，
     *   视频数据仍可能从实际存在的 bulk-IN 端点流出。这是一次**廉价验证** ——
     *   收到帧即证明可行；收不到则明确判定该机型不支持。
     *   风险为零：正常设备在 ① 就返回，永不走到 ②。
     */
    public static UsbEndpoint findAnyBulkIn(UsbDevice d) {
        UsbEndpoint cdcFallback = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            int c = itf.getInterfaceClass();
            boolean isCdc = (c == 2 || c == 10);
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                if (ep.getDirection() != UsbConstants.USB_DIR_IN) continue;
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (isCdc) {
                    if (cdcFallback == null) cdcFallback = ep;   // 记下但先不用
                    continue;
                }
                Log.w(TAG, "兜底①：从接口 " + itf.getId() + "(class=" + c
                        + ") 取到 bulk-IN 端点 addr=0x"
                        + Integer.toHexString(ep.getAddress()));
                return ep;
            }
        }
        if (cdcFallback != null) {
            Log.w(TAG, "兜底②：仅 CDC 接口有 bulk-IN 端点 addr=0x"
                    + Integer.toHexString(cdcFallback.getAddress())
                    + "，尝试用它（Pico 4 Pro 等系统遗漏 VS 接口的场景）");
        }
        return cdcFallback;
    }

    /** 找出给定端点所属的接口（claim 时必须针对该接口） */
    public static UsbInterface interfaceOf(UsbDevice d, UsbEndpoint ep) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                if (itf.getEndpoint(j).getAddress() == ep.getAddress()) return itf;
            }
        }
        return null;
    }

    /** 描述设备全部接口（失败时打日志用，便于客户回传定位） */
    public static String describeInterfaces(UsbDevice d) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (i > 0) sb.append(", ");
            sb.append(itf.getId()).append(':')
              .append(itf.getInterfaceClass()).append('/')
              .append(itf.getInterfaceSubclass()).append('/')
              .append(itf.getInterfaceProtocol())
              .append("(ep=").append(itf.getEndpointCount()).append(')');
        }
        return sb.append(']').toString();
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
        // ★ 必须追加 PID：廉价 UVC 模组常把 serial 写成同一个假值
        //   （实测：OV9281 与 GC0308 都是 "SN0001"，VID 也都是 0C45）
        //   ⇒ 仅靠名字 + 序列号会**完全撞车**，PC 端/OBS 里两颗相机无法区分。
        //   PID 不同，用它兜底最稳（key 里本来就有 PID，这里让显示名也对齐）。
        name += String.format(java.util.Locale.US, " %04X", d.getProductId());
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
            // ★ Pico 4 Pro 兼容兜底：系统可能只暴露了 UVC VideoControl(14/1)，
            //   缺失 VideoStreaming(14/2)。此时无法通过接口拿 bulk-IN 端点。
            //   尝试：① 有 VC 接口就继续（用 VC 接口发 probe/commit，端点从设备全局找）
            //        ② 仍失败再抛明确错误，便于日志定位。
            UsbInterface vc = findVideoControlInterface(dev);
            if (vc != null) {
                Log.w(TAG, "系统未暴露 UVC VideoStreaming 接口，改用 VC 接口兜底: id="
                        + vc.getId() + "；设备接口数=" + dev.getInterfaceCount());
                vsInterface = vc;   // 后续 probe/commit 走它；端点解析走 findAnyBulkIn()
                // ★ 关键：probe/commit 必须发往 VS 接口号，但系统没枚举出 VS。
                //   标准 UVC 复合设备里 VC 与 VS 紧邻，VS 通常 = VC+1；
                //   若 VC 已是最后一个接口则退回 VC 自身。
                //   实测 Pico 4 Pro：VC=2，固件内 VS=3（发到 2 会被固件拒绝，返回 -1）。
                int guess = vc.getId() + 1;
                probeWIndex = (guess < dev.getInterfaceCount()) ? guess : vc.getId();
                Log.w(TAG, "probe/commit 的 wIndex 推测为 " + probeWIndex
                        + "（VC id=" + vc.getId() + "，接口总数=" + dev.getInterfaceCount() + "）");
            } else {
                throw new IOException("未找到 UVC 视频流接口(Class 0x0E)。"
                        + "设备接口数=" + dev.getInterfaceCount()
                        + "，接口 class=" + describeInterfaces(dev)
                        + "，请确认固件已启用 UVC 模式");
            }
        }
        Log.i(TAG, "选中 VS 接口 id=" + vsInterface.getId()
                + " class=" + vsInterface.getInterfaceClass()
                + " subclass=" + vsInterface.getInterfaceSubclass());

        connection = usbManager.openDevice(dev);
        if (connection == null) {
            throw new IOException("openDevice 失败：USB 权限可能未授予或设备被占用");
        }

        // 先确定要用的 bulk-IN 端点（可能来自 VS 接口，也可能是兜底来的）
        for (int j = 0; j < vsInterface.getEndpointCount(); j++) {
            UsbEndpoint ep = vsInterface.getEndpoint(j);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                bulkIn = ep;
                break;
            }
        }
        if (bulkIn == null) {
            // ★ Pico 4 Pro 兜底：system 未暴露 VS 接口时，vsInterface 实际是 VC 接口，
            //   其上没有端点。退化为「在设备任意接口上找 bulk-IN」（含 CDC 二级兜底）。
            bulkIn = findAnyBulkIn(dev);
        }
        if (bulkIn == null) {
            throw new IOException("找不到 bulk-IN 端点（该固件应是 bulk 模式）。接口="
                    + describeInterfaces(dev)
                    + "；若这是 Pico 4 Pro 等旧系统设备，可能是系统未暴露 VideoStreaming 接口");
        }

        // 找到该端点所属的接口 —— 必须 claim「它所在的接口」，否则 bulkTransfer 会失败。
        // （兜底场景下端点可能挂在 CDC Data 接口上，此时不能只 claim VC 接口。）
        UsbInterface epOwner = interfaceOf(dev, bulkIn);
        UsbInterface toClaim = (epOwner != null) ? epOwner : vsInterface;
        Log.i(TAG, "将 claim 接口 id=" + toClaim.getId()
                + " class=" + toClaim.getInterfaceClass()
                + "（端点 addr=0x" + Integer.toHexString(bulkIn.getAddress()) + "）");

        boolean claimed = connection.claimInterface(toClaim, true);
        if (!claimed) {
            // 部分内核在 claim 前需先 setInterface(alt 0)
            try { connection.setInterface(toClaim); } catch (Exception ignored) {}
            claimed = connection.claimInterface(toClaim, true);
        }
        if (!claimed) {
            throw new IOException("claimInterface 失败（接口 " + toClaim.getId()
                    + " 被占用？）。接口=" + describeInterfaces(dev));
        }

        // ---- 握手：SET_CUR(PROBE) -> SET_CUR(COMMIT) ----
        byte[] probe = buildProbe26(interval100ns);
        // probeWIndex：正常设备 = VS 接口 id（与旧行为完全一致）；
        // 仅在 VS 接口缺失的兜底场景下 = 推测的 VS 接口号。
        int vsId = (probeWIndex >= 0) ? probeWIndex : vsInterface.getId();
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
        probeWIndex = -1;   // 下次 start() 重新判定（避免复用实例时残留推测值）
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
