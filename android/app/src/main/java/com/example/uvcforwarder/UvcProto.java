package com.example.uvcforwarder;

/**
 * UVC / 传输协议常量。
 * 针对目标设备：XIAO ESP32-S3 (OpenIris-ESPIDF 固件, TinyUSB UVC)。
 * 已核对该固件 managed_components 中 tinyusb video_device.c 源码确认：
 *  - 设备 = CDC 串口 + UVC 复合设备，UVC 走 BULK 传输（非标准免驱摄像头的 ISO）
 *  - dwMaxPayloadTransferSize 会被固件无条件 clamp 到 64（FS bulk 端点缓冲）
 *  - payload 头 2 字节：byte0=bHeaderLength(=2)，byte1 的 bit1(0x02)=EOF
 *  - 合法帧参数固定：bFormatIndex=1, bFrameIndex=1, MJPEG 240x240
 */
public final class UvcProto {
    private UvcProto() {}

    // ---- USB 类定义 ----
    public static final int USB_CLASS_VIDEO      = 0x0E; // bInterfaceClass (UVC)
    public static final int VS_SUBCLASS_STREAMING = 0x02; // Video Streaming 子类

    // ---- UVC 类请求（发往 VideoStreaming 接口）----
    public static final int REQ_SET_CUR = 0x01;
    public static final int REQ_GET_CUR = 0x81;
    public static final int SEL_VS_PROBE_CONTROL  = 0x0100;
    public static final int SEL_VS_COMMIT_CONTROL = 0x0200;
    /** host->device, class, interface recipient */
    public static final int BMREQ_HOST_TO_DEV = 0x21;
    /** device->host, class, interface recipient */
    public static final int BMREQ_DEV_TO_HOST = 0xA1;

    // ---- 固件已知的帧参数（240x240 MJPEG 单帧尺寸）----
    public static final int FMT_INDEX   = 1;              // bFormatIndex
    public static final int FRAME_INDEX = 1;              // bFrameIndex（该固件只声明 1 个 frame descriptor）
    /** dwMaxVideoFrameSize = 240*240*16/8 = 115200（固件 MJPEG 帧尺寸公式值） */
    public static final int MAX_VIDEO_FRAME_SIZE = 240 * 240 * 16 / 8;
    /** dwMaxPayloadTransferSize：写 512，固件 commit 后会自行 clamp 到 64 */
    public static final int MAX_PAYLOAD_SIZE = 512;

    /** 100ns 单位。30fps 起步（FS bulk 实测上限约 40fps，60fps 不可达） */
    public static final int FRAME_INTERVAL_30 = 10000000 / 30; // 333333
    public static final int FRAME_INTERVAL_60 = 10000000 / 60; // 166666

    /** VS_PROBE_CONTROL / COMMIT 结构（UVC 1.0/1.1 版 26 字节；固件按 1.5 结构接收也兼容） */
    public static final int PROBE_LEN = 26;

    /** 一帧 JPEG 的硬上限，防止异常数据把内存撑爆 */
    public static final int MAX_FRAME_BYTES = 512 * 1024;

    // ---- 网络分片协议 v2 (多流) ----
    /** 数据报头部魔数 'U''V' */
    public static final byte[] NET_MAGIC = {'U', 'V'};
    /** 头布局: magic2 + streamId1 + seq4 + fidx2 + fcnt2 = 11 字节 */
    public static final int NET_HEADER_LEN = 11;
    public static final int NET_STREAM_ID_OFFSET = 2;
    public static final int NET_PAYLOAD_MAX = 1200;  // 每片数据上限（< MTU 稳妥）
    /** 控制/元数据包专用 stream-id（不承载视频帧） */
    public static final int NET_SID_META = 255;
    /** 支持的最大并发路数 */
    public static final int MAX_STREAMS = 6;
    public static final int NET_PORT_DEFAULT = 5000;
    public static final int NET_BEACON_PORT = 5001;
    public static final String NET_DISCOVER_MSG = "UVC-FORWARDER-DISCOVER";
    public static final String NET_REPLY_PREFIX = "UVC-RECEIVER ";

    // ---- 状态广播 ----
    public static final String ACTION_STATUS = "com.example.uvcforwarder.STATUS";
    public static final String EXTRA_STATUS = "status_text";
    /** 设备级状态广播（MainActivity 设备列表用） */
    public static final String ACTION_DEVICE_STATE = "com.example.uvcforwarder.DEVICE_STATE";
    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_DEVICE_ACTIVE = "device_active";
    /** Service 单设备操作命令 */
    public static final String EXTRA_DEVICE_ACTION = "device_action";
    public static final String DEVICE_ACTION_START = "start";
    public static final String DEVICE_ACTION_STOP = "stop";
}
