package com.example.uvcforwarder;

import android.util.Log;

/**
 * JNI 绑定：libusb 原生传输层（实现见 cpp/rawusb_jni.c）。
 *
 * <p><b>为什么需要</b>：Android 的 {@code UsbRequest} 官方只支持 BULK / INTERRUPT 端点，
 * <b>不支持等时(ISO)端点</b>；而绝大多数免驱 UVC 相机用的正是 ISO。
 * 因此 ISO 取流只能走 libusb —— 顺带这也完全绕开了 Android 的接口枚举模型。
 *
 * <p>用法：{@code h = nativeOpen(conn.getFileDescriptor())} → claim → setAlt → isoRead。
 */
public final class RawUsb {

    private static final String TAG = "UVC_RAW_JNI";

    /** 原生库是否可用（没有对应 ABI 的 .so 时为 false，调用方需优雅退化） */
    public static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("rawusb");
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "librawusb 加载失败，ISO 绕过路径不可用: " + t);
            ok = false;
        }
        AVAILABLE = ok;
    }

    private RawUsb() {}

    /** fd → libusb 句柄；失败返回 0 */
    public static native long nativeOpen(int fd);

    public static native boolean nativeClaim(long h, int itf);

    public static native boolean nativeSetAlt(long h, int itf, int alt);

    /** @return 实际字节数；负值 = libusb 错误码 */
    public static native int nativeIsoRead(long h, int epAddr, byte[] buf, int len,
                                           int packets, int timeoutMs);

    public static native int nativeBulkRead(long h, int epAddr, byte[] buf, int len,
                                            int timeoutMs);

    public static native void nativeClose(long h);
}
