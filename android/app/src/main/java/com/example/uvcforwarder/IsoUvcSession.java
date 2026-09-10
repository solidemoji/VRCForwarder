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
    /** YUY2->JPEG 编码质量(0-100)。质量低=体积小=网络更稳 */
    private static final int JPEG_QUALITY = 80;

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
            // 默认 frame format 即 MJPEG，取设备支持的 MJPEG 尺寸列表
            List<Size> sizes = camera.getSupportedSizeList();
            int w = 640, h = 400;
            boolean sizeOk = false;
            if (sizes != null && !sizes.isEmpty()) {
                // 实测：帧回调给的是解码后的 YUY2(2B/px)而非原始 JPEG，需软编码；
                // 优先尝试用户实测可用的 640x400 / 640x480（编码开销小），
                // 再按列表顺序兜底逐个尝试（各尺寸支持的 fps 档不同）
                int[][] preferred = {{640, 400}, {640, 480}, {320, 240}};
                StringBuilder tried = new StringBuilder();
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
                    // 优先档都不行：按列表顺序逐个试
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
                    sizeOk = true;
                }
                Log.i(TAG, device.getDeviceName() + " 协商尺寸 " + w + "x" + h
                        + (tried.length() > 0 ? " (失败: " + tried + ")" : ""));
            } else {
                camera.setPreviewSize(w, h, 1, TARGET_FPS, UVCCamera.FRAME_FORMAT_MJPEG, 1.0f);
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
                        out = data; // 原始 JPEG（部分设备/版本直接给）
                    } else if (data.length == fw * fh * 2) {
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
