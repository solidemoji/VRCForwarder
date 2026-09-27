package com.example.uvcforwarder;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * UVC 描述符读取与解析（自研，不依赖 saki 库的能力枚举）。
 *
 * <p>为什么必须自己读：saki 的 {@code getSupportedSize()} JSON **只给尺寸、没有帧率**，
 * 且无法表达"同一尺寸的多套帧率"（GC0308 的 320x240 在列表里出现两次就是这个信息被压平的结果）。
 * 要做「格式 × 分辨率 × 帧率」选择，只能解析 VS 接口的 format/frame 描述符
 * （frame 描述符里的 {@code dwFrameInterval[]} 才是真实帧率表）。
 *
 * <p>读取方式：对设备发 {@code GET_DESCRIPTOR(CONFIGURATION)} 标准控制传输 ——
 * App 已持有该设备的 USB 权限，不需要 root，也不依赖 sysfs（sysfs 在 Pico/Quest 上被 SELinux 挡住）。
 */
public final class UvcDescriptors {

    private static final String TAG = "UVC_DESC";

    private static final int DT_CONFIGURATION = 0x02;
    private static final int DT_INTERFACE = 0x04;
    private static final int CS_INTERFACE = 0x24;   // 类专用描述符的 bDescriptorType

    // VideoStreaming 接口的子类型
    private static final int VS_FORMAT_UNCOMPRESSED = 0x04;
    private static final int VS_FRAME_UNCOMPRESSED = 0x05;
    private static final int VS_FORMAT_MJPEG = 0x06;
    private static final int VS_FRAME_MJPEG = 0x07;
    private static final int VS_FORMAT_FRAME_BASED = 0x0A;
    private static final int VS_FRAME_FRAME_BASED = 0x0B;

    /** 与 UVCCamera.FRAME_FORMAT_YUYV 一致 */
    public static final int FRAME_FORMAT_YUYV = 0;
    /** 与 UVCCamera.FRAME_FORMAT_MJPEG 一致 */
    public static final int FRAME_FORMAT_MJPEG = 1;

    /** USB 帧间隔单位是 100ns ⇒ fps = 10_000_000 / interval */
    private static final float FPS_SCALE = 10000000f;

    private UvcDescriptors() {}

    // ------------------------------------------------------------------ 数据模型

    /** 一个帧描述符 = 一种「分辨率 + 帧率集合」 */
    public static final class Frame {
        public int index;
        public int width, height;
        /** 100ns 单位 */
        public int defaultInterval;
        /** 离散帧间隔（100ns）；continuous 时为空 */
        public int[] intervals;
        /** 连续区间时的最小/最大间隔与步长（100ns） */
        public int minInterval, maxInterval, intervalStep;
        public boolean continuous;

        /** 支持的帧率（降序）。连续区间给出 [最高, 最低] 两端。未知则空数组。 */
        public float[] fps() {
            if (continuous) {
                if (minInterval <= 0 || maxInterval <= 0) return new float[0];
                // 间隔越小 → 帧率越高
                return new float[]{FPS_SCALE / minInterval, FPS_SCALE / maxInterval};
            }
            if (intervals == null || intervals.length == 0) return new float[0];
            float[] r = new float[intervals.length];
            for (int i = 0; i < intervals.length; i++) {
                r[i] = intervals[i] > 0 ? FPS_SCALE / intervals[i] : 0f;
            }
            Arrays.sort(r);
            for (int i = 0, j = r.length - 1; i < j; i++, j--) {
                float t = r[i]; r[i] = r[j]; r[j] = t;      // 降序
            }
            return r;
        }

        public int maxFpsInt() {
            float[] f = fps();
            return f.length == 0 ? 0 : Math.round(f[0]);
        }

        /** 该帧能否支撑 target fps（帧率表未知时返回 true，不排除） */
        public boolean supportsFps(int target) {
            float[] f = fps();
            if (f.length == 0) return true;
            if (continuous) return target <= f[0] + 0.5f && target >= f[f.length - 1] - 0.5f;
            for (float v : f) {
                if (Math.abs(v - target) <= 1.5f) return true;
            }
            return false;
        }

        /** 可读帧率文本 */
        public String fpsText() {
            float[] f = fps();
            if (f.length == 0) return "(未知)";
            if (continuous) {
                return String.format(Locale.US, "%.0f~%.0f(连续)", f[0], f[f.length - 1]);
            }
            StringBuilder b = new StringBuilder();
            for (float v : f) b.append(String.format(Locale.US, "%.0f ", v));
            return b.toString().trim();
        }
    }

    /** 一个格式描述符（MJPEG / YUYV / ...） */
    public static final class Format {
        public int subtype;
        /** 对应 saki 的 FRAME_FORMAT_* */
        public int frameFormat;
        public String kind;
        public final List<Frame> frames = new ArrayList<>();

        @Override
        public String toString() {
            return kind + "(" + frames.size() + " 帧档)";
        }
    }

    /** 选出来的目标规格 */
    public static final class Choice {
        public Format format;
        public Frame frame;
        public int fps;
        public String describe() {
            return format.kind + " " + frame.width + "x" + frame.height + "@" + fps;
        }
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 读取原始配置描述符。
     * 先取 9 字节拿到 wTotalLength，再按该长度整取（标准两步法）。
     */
    public static byte[] readConfigDescriptor(UsbDeviceConnection conn) {
        if (conn == null) return null;
        try {
            byte[] head = new byte[9];
            int n = conn.controlTransfer(0x80, 0x06, 0x0200, 0, head, head.length, 1000);
            if (n < 9) {
                Log.w(TAG, "取配置描述符头部失败 n=" + n);
                return null;
            }
            int total = (head[2] & 0xFF) | ((head[3] & 0xFF) << 8);
            if (total < 9 || total > 8192) {
                Log.w(TAG, "wTotalLength 异常: " + total);
                return null;
            }
            byte[] buf = new byte[total];
            n = conn.controlTransfer(0x80, 0x06, 0x0200, 0, buf, total, 2000);
            if (n < 9) {
                Log.w(TAG, "取完整配置描述符失败 n=" + n);
                return null;
            }
            return buf;
        } catch (Exception e) {
            Log.w(TAG, "读描述符异常: " + e);
            return null;
        }
    }

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    private static Format newFormat(int subtype) {
        Format f = new Format();
        f.subtype = subtype;
        switch (subtype) {
            case VS_FORMAT_MJPEG:
                f.kind = "MJPEG";
                f.frameFormat = FRAME_FORMAT_MJPEG;
                break;
            case VS_FORMAT_UNCOMPRESSED:
                f.kind = "YUYV";
                f.frameFormat = FRAME_FORMAT_YUYV;
                break;
            case VS_FORMAT_FRAME_BASED:
                f.kind = "FRAMEBASED";
                f.frameFormat = FRAME_FORMAT_MJPEG;   // 常见为 H.264 等，先按 MJPEG 通道走
                break;
            default:
                f.kind = String.format(Locale.US, "SUB%02X", subtype);
                f.frameFormat = FRAME_FORMAT_MJPEG;
        }
        return f;
    }

    /** 解析一个 frame 描述符；布局随子类型不同 */
    private static Frame parseFrame(byte[] d, int off, int len, int subtype) {
        try {
            Frame f = new Frame();
            f.index = d[off + 3] & 0xFF;

            int p;
            if (subtype == VS_FRAME_FRAME_BASED) {
                // 0 len,1 type,2 sub,3 idx,4 caps,5-6 w,7-8 h,9-12 minBr,13-16 maxBr,
                // 17-20 dwDefaultFrameInterval, 21 bFrameIntervalType, 22+ intervals
                f.width = u16(d, off + 5);
                f.height = u16(d, off + 7);
                f.defaultInterval = u32(d, off + 17);
                p = off + 21;
            } else {
                // 0 len,1 type,2 sub,3 idx,4 caps,5-6 w,7-8 h,9-12 minBr,13-16 maxBr,
                // 17-20 dwMaxVideoFrameBufferSize, 21-24 dwDefaultFrameInterval,
                // 25 bFrameIntervalType, 26+ intervals
                f.width = u16(d, off + 5);
                f.height = u16(d, off + 7);
                f.defaultInterval = u32(d, off + 21);
                p = off + 25;
            }
            if (f.width <= 0 || f.height <= 0) return null;
            if (p >= off + len) return f;            // 没有帧率字段

            int fit = d[p] & 0xFF;
            p += 1;
            if (fit == 0) {
                f.continuous = true;
                if (p + 12 <= off + len) {
                    f.minInterval = u32(d, p);
                    f.maxInterval = u32(d, p + 4);
                    f.intervalStep = u32(d, p + 8);
                }
            } else {
                int cnt = Math.min(fit, (off + len - p) / 4);
                f.intervals = new int[Math.max(0, cnt)];
                for (int k = 0; k < cnt; k++, p += 4) {
                    f.intervals[k] = u32(d, p);
                }
            }
            return f;
        } catch (Exception e) {
            Log.w(TAG, "解析帧描述符异常: " + e);
            return null;
        }
    }

    /** 解析配置描述符，产出 格式 → 帧 表 */
    public static List<Format> parse(byte[] cfg) {
        List<Format> out = new ArrayList<>();
        if (cfg == null || cfg.length < 9) return out;

        Format curFmt = null;
        int i = 0;
        int n = cfg.length;
        while (i + 2 <= n) {
            int blen = cfg[i] & 0xFF;
            int btype = cfg[i + 1] & 0xFF;
            if (blen < 2 || i + blen > n) break;

            if (btype == DT_CONFIGURATION) {
                // 不重置：配置头之后就是接口
            } else if (btype == DT_INTERFACE) {
                int cls = cfg[i + 5] & 0xFF;
                int sub = cfg[i + 6] & 0xFF;
                // 只在 VideoStreaming 接口(class 0x0E sub 0x02)里收集 format/frame；
                // 进入别的接口就清掉上下文，避免把 VideoControl 的类专用描述符算进来。
                if (!(cls == 0x0E && sub == 0x02)) curFmt = null;
            } else if (btype == CS_INTERFACE) {
                int st = cfg[i + 2] & 0xFF;
                if (st == VS_FORMAT_MJPEG || st == VS_FORMAT_UNCOMPRESSED
                        || st == VS_FORMAT_FRAME_BASED) {
                    curFmt = newFormat(st);
                    out.add(curFmt);
                } else if ((st == VS_FRAME_MJPEG || st == VS_FRAME_UNCOMPRESSED
                        || st == VS_FRAME_FRAME_BASED) && curFmt != null) {
                    Frame f = parseFrame(cfg, i, blen, st);
                    if (f != null) curFmt.frames.add(f);
                }
            }
            i += blen;
        }
        return dedupe(out);
    }

    /**
     * 去重：VS 接口有多个备用设置(alt)，同一 format/frame 会在每个 alt 里重复出现。
     * 按「格式 + 分辨率」合并，帧率取并集。
     */
    private static List<Format> dedupe(List<Format> in) {
        List<Format> out = new ArrayList<>();
        for (Format f : in) {
            Format target = null;
            for (Format o : out) {
                if (o.subtype == f.subtype) { target = o; break; }
            }
            if (target == null) {
                target = newFormat(f.subtype);
                target.kind = f.kind;
                target.frameFormat = f.frameFormat;
                out.add(target);
            }
            for (Frame fr : f.frames) {
                Frame exist = null;
                for (Frame g : target.frames) {
                    if (g.width == fr.width && g.height == fr.height) { exist = g; break; }
                }
                if (exist == null) {
                    target.frames.add(fr);
                } else {
                    // 合并帧率并集
                    exist.intervals = mergeIntervals(exist.intervals, fr.intervals);
                    if (exist.defaultInterval == 0) exist.defaultInterval = fr.defaultInterval;
                    exist.continuous = exist.continuous || fr.continuous;
                }
            }
        }
        return out;
    }

    private static int[] mergeIntervals(int[] a, int[] b) {
        if (a == null || a.length == 0) return b;
        if (b == null || b.length == 0) return a;
        int[] c = new int[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        Arrays.sort(c);
        // 去重
        int w = 0;
        for (int i = 0; i < c.length; i++) {
            if (i == 0 || c[i] != c[i - 1]) c[w++] = c[i];
        }
        return Arrays.copyOf(c, w);
    }

    /** 一站式：读 + 解析（含日志） */
    public static List<Format> read(UsbDeviceConnection conn, UsbDevice dev) {
        byte[] cfg = readConfigDescriptor(conn);
        if (cfg == null) {
            Log.w(TAG, "读不到配置描述符: " + (dev != null ? dev.getDeviceName() : "?"));
            return new ArrayList<>();
        }
        Log.i(TAG, (dev != null ? dev.getDeviceName() : "?")
                + " 配置描述符 " + cfg.length + " 字节");
        List<Format> fmts = parse(cfg);
        logTable(fmts, dev != null ? dev.getDeviceName() : "?");
        return fmts;
    }

    /**
     * 同 {@link #read}，但**不打日志** —— 给 UI 侧的规格预测用（列表每次刷新都会读，
     * 打日志会刷屏）。失败返回空表。
     */
    public static List<Format> readQuiet(UsbDeviceConnection conn) {
        byte[] cfg = readConfigDescriptor(conn);
        return cfg == null ? new ArrayList<Format>() : parse(cfg);
    }

    /** 把能力表打进日志（诊断用，也让"实测确认 4/6 语义"有据可依） */
    public static void logTable(List<Format> fmts, String prefix) {
        if (fmts == null || fmts.isEmpty()) {
            Log.w(TAG, prefix + " 未解析到 VS format/frame 描述符（能力表为空）");
            return;
        }
        Log.i(TAG, "=== 真实能力表 " + prefix + " ===");
        for (Format f : fmts) {
            for (Frame fr : f.frames) {
                Log.i(TAG, "  " + f.kind + " " + fr.width + "x" + fr.height
                        + " 帧率=" + fr.fpsText()
                        + " 默认间隔=" + fr.defaultInterval
                        + " 离散=" + (fr.intervals != null ? fr.intervals.length : 0)
                        + " 连续=" + fr.continuous);
            }
        }
    }

    // ------------------------------------------------------------------ 选择策略

    /**
     * 选择策略（默认，可被 UI 覆盖）：
     * 在「支持目标帧率」且不超过 maxW×maxH 的候选里，取**面积最大**的；
     * 面积相同则优先 MJPEG（体积小、带宽友好）。
     * 若没有任何候选支持目标帧率，退化为「该上限内帧率最高」的档（帧率相同取大分辨率）。
     *
     * <p>之所以要 maxW/maxH 上限：实测 OV9281 在 1280x800@120 档等时带宽撑不住
     * （回调给出 2MB 异常帧），而眼追场景 640x480 已足够。
     */
    public static Choice selectBest(List<Format> fmts, int targetFps, int maxW, int maxH) {
        if (fmts == null || fmts.isEmpty()) return null;

        Choice best = null;
        for (Format f : fmts) {
            for (Frame fr : f.frames) {
                if (fr.width <= 0 || fr.height <= 0) continue;
                if (fr.width > maxW || fr.height > maxH) continue;
                if (!fr.supportsFps(targetFps)) continue;
                if (best == null || betterForTarget(f, fr, best)) {
                    best = newChoice(f, fr, targetFps);
                }
            }
        }
        if (best != null) return best;

        // 退化：帧率最高优先
        for (Format f : fmts) {
            for (Frame fr : f.frames) {
                if (fr.width <= 0 || fr.height <= 0) continue;
                if (fr.width > maxW || fr.height > maxH) continue;
                int mf = fr.maxFpsInt();
                if (mf <= 0) continue;
                if (best == null) { best = newChoice(f, fr, mf); continue; }
                int bmf = best.frame.maxFpsInt();
                int area = fr.width * fr.height;
                int barea = best.frame.width * best.frame.height;
                if (mf > bmf || (mf == bmf && area > barea)) {
                    best = newChoice(f, fr, mf);
                }
            }
        }
        return best;
    }

    private static Choice newChoice(Format f, Frame fr, int fps) {
        Choice c = new Choice();
        c.format = f;
        c.frame = fr;
        c.fps = fps;
        return c;
    }

    /** 同为"满足目标帧率"时：面积大者优先；面积相同优先 MJPEG */
    private static boolean betterForTarget(Format f, Frame fr, Choice best) {
        int area = fr.width * fr.height;
        int barea = best.frame.width * best.frame.height;
        if (area != barea) return area > barea;
        boolean mjpeg = f.frameFormat == FRAME_FORMAT_MJPEG;
        boolean bmjpeg = best.format.frameFormat == FRAME_FORMAT_MJPEG;
        return mjpeg && !bmjpeg;
    }
}
