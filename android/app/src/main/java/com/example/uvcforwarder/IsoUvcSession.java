package com.example.uvcforwarder;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.util.List;

/**
 * ISO 会话：标准免驱 UVC 摄像头（Isochronous 等时传输，如 OV9281 板）。
 *
 * 实现要点（踩坑修正）：
 *  - Android Java USB API 不支持等时传输 → 走 saki UVCCamera(JNI libuvc)；
 *  - 不用 USBMonitor.register() 的自动连接线程：其内部轮询在部分系统上
 *    (createNewFile 失败)会反复异常导致永远到不了 onConnect；
 *    改为创建 USBMonitor 后直接 openDevice() 手动拿 UsbControlBlock
 *    （设备权限由 MainActivity 先行授予）。
 *  - 帧回调 PIXEL_FORMAT_RAW = 原始 MJPEG 字节，零转码直接转发。
 */
public class IsoUvcSession {

    private static final String TAG = "UVC_ISO";

    public interface Listener {
        /** 完整 MJPEG 帧（native 回调线程触发，须快速处理） */
        void onFrame(byte[] jpeg);
        void onState(String text);
    }

    /** 目标帧率上限：OV9281 高帧模式为 120fps；不匹配时 libuvc 就近匹配 */
    private static final int TARGET_FPS = 120;
    /**
     * 选规格时的分辨率上限（默认策略）。
     * 实测：OV9281 的 1280x800@120 档等时带宽撑不住（回调给出 2MB 异常帧），
     * 而眼追/面捕场景 640x480 已足够 ⇒ 自动模式下不超过 640x480。
     * 将来由「手动选规格」UI 覆盖。
     */
    private static final int MAX_W = 640;
    private static final int MAX_H = 480;
    /** YUY2->JPEG 编码质量(0-100)。质量低=体积小=网络更稳 */
    private static final int JPEG_QUALITY = 80;

    // ---- 相机能力（枚举一次，缓存；供诊断日志与将来的「手动选规格」UI 使用）----
    /** saki native 输出的原始能力 JSON（formats[].type + size[] + 各尺寸帧率） */
    private String capabilityJson;
    /** MJPEG 组可选尺寸（每项自带 fps 表） */
    private List<Size> mjpegSizes;
    /** YUYV 组可选尺寸 */
    private List<Size> yuyvSizes;

    public String getCapabilityJson() { return capabilityJson; }
    public List<Size> getMjpegSizes() { return mjpegSizes; }
    public List<Size> getYuyvSizes() { return yuyvSizes; }

    /** 自研描述符解析出的**真实能力表**（格式 × 分辨率 × 帧率）；null/空 = 读取失败 */
    private List<UvcDescriptors.Format> capabilityTable;
    /** 本次实际选中的规格（供 UI 显示 / 日志核对） */
    private UvcDescriptors.Choice activeChoice;

    public List<UvcDescriptors.Format> getCapabilityTable() { return capabilityTable; }
    public UvcDescriptors.Choice getActiveChoice() { return activeChoice; }

    /** 本次实际生效的规格文本（形如 "MJPEG 320x240@120"），供日志/UI 显示 */
    private String activeSpecText;

    public String getActiveSpecText() { return activeSpecText; }

    /**
     * 预测「自动模式」会选中哪个规格 —— 供 UI 在**尚未转发**时也能显示「自动（MJPEG 640x480@120）」。
     * 与真正开流时走同一套策略（{@link UvcDescriptors#selectBest}），所以预测值＝实际值。
     *
     * @return 形如 "MJPEG 640x480@120"；表不可用时返回 null
     */
    public static String predictAutoSpec(List<UvcDescriptors.Format> table) {
        UvcDescriptors.Choice c =
                UvcDescriptors.selectBest(table, TARGET_FPS, MAX_W, MAX_H);
        return c == null ? null : c.describe();
    }

    private final Context context;
    private final UsbDevice target;
    private final FrameSender sender;
    private final Listener listener;

    private USBMonitor monitor;
    private USBMonitor.UsbControlBlock ctrlBlock;
    private UVCCamera camera;
    /** 虚拟显示目标：saki native 预览需绑定 Surface 才启动等时传输，无头模式用它占位 */
    private android.graphics.SurfaceTexture dummyTexture;
    private volatile boolean opened = false;
    private volatile boolean running = false;
    /** 是否已记录帧形态（只记一次，避免刷屏） */
    private volatile boolean frameFormLogged = false;

    private long statFrames = 0;
    private long statBytes = 0;
    private long statStart = 0;
    private volatile float curFps = 0;

    public IsoUvcSession(Context context, UsbDevice target, FrameSender sender, Listener listener) {
        this.context = context.getApplicationContext();
        this.target = target;
        this.sender = sender;
        this.listener = listener;
    }

    /** USBMonitor 构造要求 listener 非空；我们不走自动连接，用 no-op 占位 */
    private static final USBMonitor.OnDeviceConnectListener NOOP_LISTENER =
            new USBMonitor.OnDeviceConnectListener() {
                @Override
                public void onAttach(UsbDevice device) {}
                @Override
                public void onDettach(UsbDevice device) {}
                @Override
                public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock,
                                      boolean createNew) {}
                @Override
                public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {}
                @Override
                public void onCancel(UsbDevice device) {}
            };

    /** 启动：不依赖 USBMonitor 的自动连接线程，直接手动打开已授权设备 */
    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            monitor = new USBMonitor(context, NOOP_LISTENER);
            Log.i(TAG, target.getDeviceName() + " ISO start: monitor 就绪");
            if (!monitor.hasPermission(target)) {
                notifyState("设备未授权 USB 权限，无法以 ISO 模式打开");
                Log.w(TAG, target.getDeviceName() + " ISO start: 无权限");
                running = false;
                return;
            }
            // ★ 打开 ISO 会话**之前**读真实能力表：
            //   用一次性的 UsbDeviceConnection（读完即关），避免与 UVCCamera 持有的连接互相干扰
            readCapabilities();
            Log.i(TAG, target.getDeviceName() + " ISO start: openDevice...");
            ctrlBlock = monitor.openDevice(target);   // 内部 openDevice + UsbControlBlock
            if (ctrlBlock == null) {
                notifyState("openDevice 返回 null（设备可能正被占用）");
                Log.w(TAG, target.getDeviceName() + " ISO start: openDevice null");
                running = false;
                return;
            }
            Log.i(TAG, target.getDeviceName() + " ISO start: openDevice OK，openCamera...");
            openCamera(target, ctrlBlock);
        } catch (SecurityException e) {
            Log.e(TAG, "ISO start SecurityException: " + e.getMessage());
            notifyState("USB 权限不足: " + e.getMessage());
            running = false;
        } catch (Exception e) {
            Log.e(TAG, "start 失败", e);
            notifyState("ISO 会话启动失败: " + e.getMessage());
            running = false;
        }
    }

    private void openCamera(UsbDevice device, USBMonitor.UsbControlBlock cb) {
        try {
            camera = new UVCCamera();
            camera.open(cb);
            // saki 的能力枚举（诊断用；★ 注意它**不含帧率**，只有尺寸）
            dumpCapabilities(device);

            int w, h;
            int[] wh = null;

            // ★★ 系统枚举失败（接口数为 0）的设备：不信任能力表协商，直接用**实测有效的那组参数**。
            //    实测 GC0308 / Pico 4 / Android 10（2026-10-07）：
            //      · 自动协商选出的 MJPEG 320x240@120 → setPreviewSize 失败、拿不到帧 ✗
            //      · 手动指定 MJPEG 640x480@30 → 出流正常、PC 端收到画面 ✓（用户实测）
            //      · 不设尺寸（saki 默认 YUYV 640x480）→ App 侧也能出流 ✓
            //    所以这里主动设成 640x480@30 MJPEG；若设不上则什么都不设、退回 saki 默认。
            final boolean rawDevice = (device.getInterfaceCount() == 0);
            if (rawDevice) {
                boolean setOk = false;
                try {
                    camera.setPreviewSize(640, 480, 1, 30, UVCCamera.FRAME_FORMAT_MJPEG, 1.0f);
                    setOk = true;
                    Log.i(TAG, "接口数为 0（系统枚举失败）：采用实测有效规格 MJPEG 640x480@30");
                } catch (Throwable t) {
                    Log.w(TAG, "0 接口设备设 640x480@30 失败，退回 saki 默认: " + t);
                }
                wh = new int[]{640, 480};
                if (setOk) {
                    // 已按上面的规格设好，后面的能力表/旧协商都不再执行
                    activeSpecText = "MJPEG 640x480@30";
                }
            } else {
                wh = null;   // 非 0 接口设备：走原有协商流程
            }

            // ⓪ 最高优先：用户在「规格」UI 里手动指定过 → 照办（设置失败则自动接棒）
            UvcSpecPrefs.Spec manual = UvcSpecPrefs.parse(
                    UvcSpecPrefs.get(context, UvcStreamer.deviceKey(device)));
            if (manual != null) {
                try {
                    camera.setPreviewSize(manual.width, manual.height, 1, manual.fps,
                            manual.frameFormat, 1.0f);
                    wh = new int[]{manual.width, manual.height};
                    activeSpecText = manual.describe();
                    Log.i(TAG, device.getDeviceName() + " 采用手动规格 " + manual.describe());
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "手动规格设置失败(" + manual.describe() + "): " + e.getMessage()
                            + " → 回退自动协商");
                }
            }

            // ① 次选：用自研描述符解析出的**真实能力表**（含真实帧率）来选规格。
            //    这修正了旧逻辑的致命缺陷：旧逻辑按固定尺寸顺序 + 一成功就 break，
            //    在"小尺寸高帧率"的相机上会抢先选到低帧率档
            //    （GC0308 实测踩到：640x480 被接受但只有 30fps，320x240@120 从未被尝试）。
            if (wh == null) {
                UvcDescriptors.Choice c =
                        UvcDescriptors.selectBest(capabilityTable, TARGET_FPS, MAX_W, MAX_H);
                if (c != null) {
                    // ★ 诊断：把实际传给 saki 的实参打出来 —— 自动协商一直失败的原因必须靠实证定位
                    //   （这颗相机的描述符自相矛盾：dwDefaultFrameInterval=333333/30fps，
                    //     但 dwFrameInterval[] 列表是 83333/120fps）
                    Log.i(TAG, "能力表候选实参: w=" + c.frame.width + " h=" + c.frame.height
                            + " minFps=1 maxFps=" + c.fps
                            + " format=" + c.format.frameFormat
                            + " | 该帧 maxFpsInt=" + c.frame.maxFpsInt()
                            + " defaultInterval=" + c.frame.defaultInterval
                            + " fpsText=" + c.frame.fpsText()
                            + " | describe=" + c.describe());
                    try {
                        camera.setPreviewSize(c.frame.width, c.frame.height, 1, c.fps,
                                c.format.frameFormat, 1.0f);
                        activeChoice = c;
                        activeSpecText = c.describe();
                        wh = new int[]{c.frame.width, c.frame.height};
                        Log.i(TAG, device.getDeviceName() + " 按真实能力表选中 " + c.describe());
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "按能力表设置失败(" + c.describe() + "): " + e.getMessage()
                                + " → 回退旧协商逻辑");
                    }
                } else {
                    Log.i(TAG, device.getDeviceName()
                            + " 真实能力表不可用或无匹配档 → 回退旧协商逻辑");
                }
            }

            // ② 回退：旧协商逻辑（原样保留，保证不回归）
            if (wh == null) {
                wh = negotiateBySakiList(device);
            }
            if (wh == null) {
                // 连 saki 尺寸列表都拿不到：沿用历史硬编码值
                camera.setPreviewSize(640, 400, 1, TARGET_FPS,
                        UVCCamera.FRAME_FORMAT_MJPEG, 1.0f);
                wh = new int[]{640, 400};
            }
            w = wh[0];
            h = wh[1];
            if (activeSpecText == null) {
                activeSpecText = w + "x" + h;
            }
            final int fw = w, fh = h;
            // 无头模式关键：saki native 预览循环依赖 Surface 窗口启动传输；
            // 给一个虚拟 SurfaceTexture 占位（不消费帧，仅触发 ISO 拉流）
            dummyTexture = new android.graphics.SurfaceTexture(0);
            camera.setPreviewTexture(dummyTexture);
            camera.setFrameCallback(frame -> {
                if (!opened) return;
                try {
                    int n = frame.remaining();
                    if (n <= 0) return;
                    byte[] data = new byte[n];
                    frame.get(data);
                    byte[] out = null;
                    if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
                        if (!frameFormLogged) {
                            frameFormLogged = true;
                            Log.i(TAG, "帧形态 = 原始 JPEG（FFD8，零编码直发）len="
                                    + data.length);
                        }
                        out = data; // 原始 JPEG（部分设备/版本直接给）
                    } else if (data.length == fw * fh * 2) {
                        if (!frameFormLogged) {
                            frameFormLogged = true;
                            Log.i(TAG, "帧形态 = 解码后 YUY2（" + fw + "x" + fh
                                    + "，2B/px）→ 软编码为 JPEG");
                        }
                        // 解码后 YUY2：直接软编码为 JPEG（YuvImage 原生支持 YUY2，零转换）
                        android.graphics.YuvImage yi = new android.graphics.YuvImage(
                                data, android.graphics.ImageFormat.YUY2, fw, fh, null);
                        java.io.ByteArrayOutputStream bos =
                                new java.io.ByteArrayOutputStream(64 * 1024);
                        yi.compressToJpeg(new android.graphics.Rect(0, 0, fw, fh),
                                JPEG_QUALITY, bos);
                        out = bos.toByteArray();
                    } else {
                        Log.w(TAG, "未知帧形态 len=" + data.length + " 期望YUY2="
                                + (fw * fh * 2));
                    }
                    if (out != null && out.length >= 2 && (out[0] & 0xFF) == 0xFF
                            && (out[1] & 0xFF) == 0xD8) {
                        sender.submit(out);
                    }
                    statBytes += out != null ? out.length : data.length;
                    statFrames++;
                    long now = System.currentTimeMillis();
                    if (now - statStart >= 1000) {
                        curFps = statFrames * 1000f / (now - statStart);
                        Log.i(TAG, "转发 " + (int) curFps + " fps, 平均 "
                                + (statBytes / Math.max(1, (int) statFrames)) + " B/帧");
                        statFrames = 0;
                        statBytes = 0;
                        statStart = now;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "帧回调异常: " + e);
                }
            }, UVCCamera.PIXEL_FORMAT_RAW);
            camera.startPreview();
            opened = true;
            statStart = System.currentTimeMillis();
            notifyState("ISO 摄像头 " + fw + "x" + fh + " 出流(YUY2->JPEG 编码)");
        } catch (Exception e) {
            Log.e(TAG, "openCamera 失败", e);
            notifyState("ISO 摄像头打开失败: " + e.getMessage());
            closeAll(true);
        }
    }

    /** 读真实能力表：打开 ISO 会话前用一次性 UsbDeviceConnection，读完立即关闭 */
    private void readCapabilities() {
        try {
            android.hardware.usb.UsbManager um = (android.hardware.usb.UsbManager)
                    context.getSystemService(Context.USB_SERVICE);
            if (um == null) {
                Log.w(TAG, "能力表读取: UsbManager 不可用");
                return;
            }
            android.hardware.usb.UsbDeviceConnection dc = um.openDevice(target);
            if (dc == null) {
                Log.w(TAG, "能力表读取: openDevice 返回 null（跳过，将走旧协商逻辑）");
                return;
            }
            try {
                capabilityTable = UvcDescriptors.read(dc, target);
            } finally {
                try { dc.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "能力表读取失败: " + e);
        }
    }

    /**
     * 旧协商逻辑（基于 saki 的尺寸列表）：保留为**回退路径**，行为与历史一致。
     * 它只知道尺寸、不知道帧率，因此可能选到"API 接受、但实际只有低帧率"的档。
     *
     * @return {w,h}；拿不到尺寸列表时返回 null
     */
    private int[] negotiateBySakiList(UsbDevice device) {
        List<Size> sizes = camera.getSupportedSizeList();
        if (sizes == null || sizes.isEmpty()) return null;
        int w = 640, h = 400;
        boolean sizeOk = false;
        StringBuilder tried = new StringBuilder();
        int[][] preferred = {{640, 400}, {640, 480}, {320, 240}};
        outer:
        for (int[] ps : preferred) {
            for (Size s : sizes) {
                if (s.width == ps[0] && s.height == ps[1]) {
                    try {
                        camera.setPreviewSize(s.width, s.height, 1, TARGET_FPS,
                                UVCCamera.FRAME_FORMAT_MJPEG, 1.0f);
                        w = s.width;
                        h = s.height;
                        sizeOk = true;
                        break outer;
                    } catch (IllegalArgumentException e) {
                        tried.append(s.width).append("x").append(s.height).append(" ");
                    }
                }
            }
        }
        if (!sizeOk) {
            for (Size s : sizes) {
                try {
                    camera.setPreviewSize(s.width, s.height, 1, TARGET_FPS,
                            UVCCamera.FRAME_FORMAT_MJPEG, 1.0f);
                    w = s.width;
                    h = s.height;
                    sizeOk = true;
                    break;
                } catch (IllegalArgumentException e) {
                    tried.append(s.width).append("x").append(s.height).append(" ");
                }
            }
        }
        if (!sizeOk) {
            // 全部 MJPEG 失败：回退 YUYV(不指定 format)，回调仍是 YUY2 可编码
            Size s = sizes.get(0);
            camera.setPreviewSize(s.width, s.height);
            w = s.width;
            h = s.height;
        }
        Log.i(TAG, device.getDeviceName() + " 旧逻辑协商尺寸 " + w + "x" + h
                + (tried.length() > 0 ? " (失败: " + tried + ")" : ""));
        return new int[]{w, h};
    }

    /**
     * 枚举并打印相机能力：格式组 / 各尺寸 / 各尺寸的帧率表。
     * **零行为变更**，只做诊断 —— 同时为「手动选规格」UI 备好数据。
     *
     * 注意：saki 的 `getSupportedSize(type, json)` 里 type 是 **native 的格式分组号**，
     * 且 `getSupportedSizeList()` 会按 `mCurrentFrameFormat>0 ? 6 : 4` 过滤。
     * 所以这里把 4 / 6 两组**都**打出来，用实测确认各自对应哪种格式，**不要凭猜**。
     */
    private void dumpCapabilities(UsbDevice device) {
        try {
            capabilityJson = camera.getSupportedSize();
            mjpegSizes = UVCCamera.getSupportedSize(6, capabilityJson);
            yuyvSizes = UVCCamera.getSupportedSize(4, capabilityJson);
            if (mjpegSizes == null) mjpegSizes = new java.util.ArrayList<Size>();
            if (yuyvSizes == null) yuyvSizes = new java.util.ArrayList<Size>();
            Log.i(TAG, "=== 相机能力枚举 " + device.getDeviceName() + " ===");
            Log.i(TAG, "组6 " + mjpegSizes.size() + " 项 / 组4 " + yuyvSizes.size()
                    + " 项（4/6 的格式语义待实测确认）");
            for (Size s : mjpegSizes) {
                Log.i(TAG, "  [组6] " + s.width + "x" + s.height
                        + " type=" + s.type + " frameType=" + s.frame_type
                        + " fitType=" + s.frameIntervalType + " fps=" + fpsText(s));
            }
            for (Size s : yuyvSizes) {
                Log.i(TAG, "  [组4] " + s.width + "x" + s.height
                        + " type=" + s.type + " frameType=" + s.frame_type
                        + " fitType=" + s.frameIntervalType + " fps=" + fpsText(s));
            }
            if (capabilityJson != null) {
                Log.i(TAG, "能力 JSON 长度 " + capabilityJson.length());
                for (int i = 0; i < capabilityJson.length(); i += 900) {
                    Log.i(TAG, "JSON[" + i + "] " + capabilityJson.substring(
                            i, Math.min(capabilityJson.length(), i + 900)));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "能力枚举失败: " + e);
        }
    }

    /** 把 Size 自带的 fps 表拼成可读文本 */
    private static String fpsText(Size s) {
        if (s == null || s.fps == null || s.fps.length == 0) return "(无)";
        StringBuilder b = new StringBuilder();
        for (float f : s.fps) {
            b.append(String.format(java.util.Locale.US, "%.0f", f)).append(' ');
        }
        return b.toString().trim();
    }

    private void closeAll(boolean deviceGone) {
        try {
            if (camera != null) {
                if (!deviceGone) {
                    // 设备仍在时优雅停流；设备已拔出则跳过 stopPreview，
                    // 避免 native 触碰已断开的设备（libuvc 可能崩溃）
                    try { camera.stopPreview(); } catch (Exception ignored) {}
                }
                try { camera.destroy(); } catch (Exception ignored) {}
                camera = null;
            }
        } catch (Exception ignored) {}
        try {
            if (dummyTexture != null) {
                dummyTexture.release();
                dummyTexture = null;
            }
        } catch (Exception ignored) {}
        try {
            if (ctrlBlock != null) {
                ctrlBlock.close();
                ctrlBlock = null;
            }
        } catch (Exception ignored) {}
        opened = false;
    }

    /** 正常停止（设备仍在线）：优雅停流再释放 */
    public synchronized void stop() {
        running = false;
        closeAll(false);
        try {
            if (monitor != null) {
                monitor.destroy();
                monitor = null;
            }
        } catch (Exception ignored) {}
        Log.i(TAG, "ISO session stopped");
    }

    /** 设备拔出场景：跳过与设备的 native 交互，仅做安全释放（防崩溃） */
    public synchronized void stopForDetach() {
        running = false;
        closeAll(true);
        try {
            if (monitor != null) {
                monitor.destroy();
                monitor = null;
            }
        } catch (Exception ignored) {}
        Log.i(TAG, "ISO session stopped (detached)");
    }

    public UsbDevice getDevice() { return target; }

    public float getCurFps() { return curFps; }

    private void notifyState(String text) {
        Log.i(TAG, text);
        if (listener != null) listener.onState(text);
    }
}
