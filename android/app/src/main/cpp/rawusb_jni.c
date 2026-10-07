/*
 * ★「应用侧绕过」的原生传输层（路线 B：libusb）
 *
 * 为什么需要它：
 *   Android 的 UsbRequest 官方只支持 BULK / INTERRUPT 端点（见 UsbRequest 类文档），
 *   **不支持等时(ISO)端点** ⇒ ISO 相机无法用 Java API 读流。
 *   而等时传输正是绝大多数免驱 UVC 相机用的方式。
 *
 * 做法：
 *   用 libusb_wrap_sys_device() 把 UsbDeviceConnection 拿到的 **fd** 包成 libusb 句柄，
 *   之后 claim / setInterface(alt) / ISO 传输全部由 libusb 直接对 usbfs 操作，
 *   完全绕开 Android 的 UsbInterface/UsbEndpoint 模型 ——
 *   这也正是「系统没把接口暴露给应用」时唯一的通路。
 */
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
#include <libusb.h>

#define TAG "UVC_RAW_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static libusb_context *g_ctx = NULL;

static libusb_device_handle *h_of(jlong h) {
    return (libusb_device_handle *) (intptr_t) h;
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long) ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/** ISO 传输完成回调：只置个标志，主线程轮询 */
static void iso_done_cb(struct libusb_transfer *t) {
    if (t->user_data) *(volatile int *) t->user_data = 1;
}

/** 用 fd 包一个 libusb 句柄；失败返回 0 */
JNIEXPORT jlong JNICALL
Java_com_example_uvcforwarder_RawUsb_nativeOpen(JNIEnv *env, jclass clazz, jint fd) {
    (void) env; (void) clazz;
    if (g_ctx == NULL) {
        /* ★ Android 关键两步（必须在 libusb_init 之前设到默认上下文）：
         * ① LIBUSB_OPTION_NO_DEVICE_DISCOVERY：
         *    Android 的 App 无权扫描 /dev/bus/usb，libusb 默认的发现流程会让
         *    libusb_init 直接失败（实测返回 LIBUSB_ERROR_IO）。
         *    我们只用 wrap_sys_device 包一个**已授权**的 fd，根本不需要枚举设备。
         * ② LIBUSB_OPTION_WEAK_AUTHORITY：
         *    允许对 wrap 进来的设备 claim 接口（Android 上 App 只有 fd，没有常规权限）。 */
        libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
#if defined(LIBUSB_API_VERSION) && LIBUSB_API_VERSION >= 0x0100010A
        libusb_set_option(NULL, LIBUSB_OPTION_WEAK_AUTHORITY);
#endif
        int r = libusb_init(&g_ctx);
        if (r < 0) {
            LOGE("libusb_init 失败: %d (%s)", r, libusb_error_name(r));
            return 0;
        }
        libusb_set_option(g_ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
    }
    libusb_device_handle *h = NULL;
    int r = libusb_wrap_sys_device(g_ctx, (intptr_t) fd, &h);
    if (r < 0 || h == NULL) {
        LOGE("libusb_wrap_sys_device(fd=%d) 失败: %d (%s)", fd, r, libusb_error_name(r));
        return 0;
    }
    LOGI("libusb_wrap_sys_device(fd=%d) OK", fd);
    return (jlong) (intptr_t) h;
}

JNIEXPORT jboolean JNICALL
Java_com_example_uvcforwarder_RawUsb_nativeClaim(JNIEnv *env, jclass clazz, jlong h, jint itf) {
    (void) env; (void) clazz;
    if (!h) return JNI_FALSE;
    libusb_device_handle *dev = h_of(h);

    /* 诊断：内核驱动是否占着这个接口（Android 上一般是 0，PC 上 uvcvideo 会是 1） */
    int kd = libusb_kernel_driver_active(dev, itf);
    LOGI("kernel_driver_active(%d) => %d", itf, kd);
    if (kd == 1) {
        int d = libusb_detach_kernel_driver(dev, itf);
        LOGI("libusb_detach_kernel_driver(%d) => %d (%s)", itf, d, libusb_error_name(d));
    }

    /* 先试着释放：若占着的是**同一个 fd**（usbfs 的 claim 是按 fd 记的），这一步就能解开。
     * 释放失败（NOT_FOUND）说明是别的持有者，此时后面的 claim 仍会 BUSY。 */
    int rel = libusb_release_interface(dev, itf);
    LOGI("release_interface(%d) => %d (%s)", itf, rel, libusb_error_name(rel));

    int r = libusb_claim_interface(dev, itf);
    if (r == LIBUSB_ERROR_BUSY) {
        LOGI("claim BUSY → 等 300ms 再释放+重试（可能与系统重新枚举/其它持有者竞争）");
        usleep(300 * 1000);
        rel = libusb_release_interface(dev, itf);
        LOGI("重试前 release => %d (%s)", rel, libusb_error_name(rel));
        r = libusb_claim_interface(dev, itf);
    }
    LOGI("libusb_claim_interface(%d) => %d (%s)", itf, r, libusb_error_name(r));
    return (r == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_uvcforwarder_RawUsb_nativeSetAlt(JNIEnv *env, jclass clazz,
                                                  jlong h, jint itf, jint alt) {
    (void) env; (void) clazz;
    if (!h) return JNI_FALSE;
    int r = libusb_set_interface_alt_setting(h_of(h), itf, alt);
    LOGI("libusb_set_interface_alt_setting(%d,%d) => %d (%s)", itf, alt, r, libusb_error_name(r));
    return (r == 0) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 等时读一次。
 * @param packets 每个 URB 的分包数；每包长度 = len / packets
 * @return 实际读到字节数（负值为 libusb 错误码）
 */
JNIEXPORT jint JNICALL
Java_com_example_uvcforwarder_RawUsb_nativeIsoRead(JNIEnv *env, jclass clazz, jlong h,
                                                   jint epAddr, jbyteArray jbuf, jint len,
                                                   jint packets, jint timeoutMs) {
    if (!h || !jbuf || len <= 0 || packets <= 0) return -1;
    unsigned char *buf = (unsigned char *) malloc((size_t) len);
    if (buf == NULL) return -2;
    memset(buf, 0, (size_t) len);

    struct libusb_transfer *t = libusb_alloc_transfer(packets);
    if (t == NULL) { free(buf); return -3; }

    volatile int done = 0;
    libusb_fill_iso_transfer(t, h_of(h), (unsigned char) epAddr, buf, len, packets,
                             iso_done_cb, (void *) &done, timeoutMs);
    libusb_set_iso_packet_lengths(t, (unsigned int) (len / packets));   /* ★ 必须设，否则每包 0 长 */

    int r = libusb_submit_transfer(t);
    if (r < 0) {
        LOGE("libusb_submit_transfer 失败: %d (%s)", r, libusb_error_name(r));
        libusb_free_transfer(t);
        free(buf);
        return r;
    }

    long t0 = now_ms();
    struct timeval tv = { 0, 100 * 1000 };            /* 每次最多等 100ms */
    while (!done && (now_ms() - t0) < timeoutMs) {
        libusb_handle_events_timeout_completed(g_ctx, &tv, NULL);
    }

    int got = (int) t->actual_length;
    int st  = (int) t->status;
    if (got <= 0) {
        LOGI("ISO 读: ep=0x%02x len=%d packets=%d => %d 字节, status=%d (耗时 %ldms)",
             epAddr, len, packets, got, st, now_ms() - t0);
    } else {
        LOGI("ISO 读: ep=0x%02x => %d 字节 ✅", epAddr, got);
        (*env)->SetByteArrayRegion(env, jbuf, 0, got, (const jbyte *) buf);
    }
    libusb_free_transfer(t);
    free(buf);
    return (jint) got;
}

/** 批量读一次（BULK 设备用） */
JNIEXPORT jint JNICALL
Java_com_example_uvcforwarder_RawUsb_nativeBulkRead(JNIEnv *env, jclass clazz, jlong h,
                                                    jint epAddr, jbyteArray jbuf, jint len,
                                                    jint timeoutMs) {
    if (!h || !jbuf || len <= 0) return -1;
    unsigned char *buf = (unsigned char *) malloc((size_t) len);
    if (buf == NULL) return -2;
    int transferred = 0;
    int r = libusb_bulk_transfer(h_of(h), (unsigned char) epAddr, buf, len, &transferred, timeoutMs);
    if (r < 0) {
        LOGE("libusb_bulk_transfer(0x%02x) 失败: %d (%s)", epAddr, r, libusb_error_name(r));
        free(buf);
        return r;
    }
    if (transferred > 0) {
        (*env)->SetByteArrayRegion(env, jbuf, 0, transferred, (const jbyte *) buf);
    }
    LOGI("BULK 读 0x%02x => %d 字节", epAddr, transferred);
    free(buf);
    return transferred;
}

JNIEXPORT void JNICALL
Java_com_example_uvcforwarder_RawUsb_nativeClose(JNIEnv *env, jclass clazz, jlong h) {
    (void) env; (void) clazz;
    if (h) {
        libusb_close(h_of(h));
        LOGI("libusb_close OK");
    }
}
