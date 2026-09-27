package com.example.uvcforwarder;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Locale;

/**
 * 每台相机的「视频规格」偏好（按稳定身份 key 存储）。
 *
 * <p>值是字符串：
 * <ul>
 *   <li>{@link #AUTO}（默认）= 自动协商（由描述符能力表 + 策略挑选）</li>
 *   <li>{@code "格式:宽:高:帧率"}，例如 {@code "1:320:240:120"}
 *       （格式用 saki 的 FRAME_FORMAT_*：0=YUYV、1=MJPEG）</li>
 * </ul>
 *
 * <p>按 key（形如 {@code 1:0C45:636D:SN0001}）存，所以同一颗相机插到哪个口都记住；
 * 不同相机互不影响（这正是"两颗相机同名"时也必须修 PID 的原因）。
 */
public final class UvcSpecPrefs {

    private static final String FILE = "uvc_spec";
    public static final String AUTO = "auto";

    private UvcSpecPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 归一化 key（SharedPreferences 的键不能为空/含奇怪字符） */
    private static String norm(String key) {
        if (TextUtils.isEmpty(key)) return "unknown";
        return "d_" + key.replace(':', '_').replace(' ', '_');
    }

    public static String get(Context c, String deviceKey) {
        String v = sp(c).getString(norm(deviceKey), AUTO);
        return TextUtils.isEmpty(v) ? AUTO : v;
    }

    public static void set(Context c, String deviceKey, String spec) {
        sp(c).edit().putString(norm(deviceKey), TextUtils.isEmpty(spec) ? AUTO : spec).apply();
    }

    public static void clear(Context c, String deviceKey) {
        sp(c).edit().remove(norm(deviceKey)).apply();
    }

    /** 手动规格解析结果 */
    public static final class Spec {
        public final int frameFormat;
        public final int width, height, fps;

        Spec(int f, int w, int h, int fps) {
            this.frameFormat = f;
            this.width = w;
            this.height = h;
            this.fps = fps;
        }

        public String encode() {
            return String.format(Locale.US, "%d:%d:%d:%d", frameFormat, width, height, fps);
        }

        public String describe() {
            return (frameFormat == UvcDescriptors.FRAME_FORMAT_MJPEG ? "MJPEG" : "YUYV")
                    + " " + width + "x" + height + "@" + fps;
        }
    }

    /** 解析偏好串；非手动或格式非法时返回 null（= 走自动） */
    public static Spec parse(String spec) {
        if (TextUtils.isEmpty(spec) || AUTO.equals(spec)) return null;
        String[] p = spec.split(":");
        if (p.length != 4) return null;
        try {
            return new Spec(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String encode(int frameFormat, int w, int h, int fps) {
        return new Spec(frameFormat, w, h, fps).encode();
    }
}
