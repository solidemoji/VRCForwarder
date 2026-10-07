package com.example.uvcforwarder;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * ★「应用侧绕过」—— 绕开 Android 的 USB 接口枚举。
 *
 * <p><b>要解决的问题</b>：Android 10（实测 Pico 4）上部分相机的描述符会让系统的
 * {@code UsbDescriptorParser} 抛异常并中止解析 ⇒ 系统给出的接口数为 <b>0</b>，
 * 于是 {@link android.hardware.usb.UsbDevice#getInterfaceCount()} 为 0，
 * 任何 App 都无法 claim 接口、无法读流。但实测这些相机的**原始描述符是完好的**
 * （我们用 {@code GET_DESCRIPTOR} 自己读得到，里面视频接口/端点一应俱全）。
 *
 * <p><b>本类做的事</b>：用反射构造出 {@link UsbInterface}/{@link UsbEndpoint}，
 * 按**我们自己从原始描述符解析出的接口号**去 claim，再直接对端点做 I/O ——
 * 完全不依赖 Android 的接口枚举结果。
 *
 * <p><b>为什么控制传输不需要绕过</b>：{@code controlTransfer()} 是设备级请求，
 * 只需要 fd（{@code openDevice()} 拿到的 {@link UsbDeviceConnection}），
 * 所以 UVC 的 probe/commit 本来就能发；缺的只有「接口 + 端点对象」这一层。
 *
 * <p><b>风险</b>：反射用到的都是 AOSP 的隐藏 API（{@code @UnsupportedAppUsage}）。
 * Android 9+ 对非 SDK 接口有灰/黑名单限制；本类把每一步都单独 try/catch 并打日志，
 * 好确认到底卡在哪一步（只要没被 blacklist 拦，这条路就成立）。
 */
public final class RawUvc {

    private static final String TAG = "UVC_RAW";

    /** 反射是否已被系统拦截（用了被 blacklist 的接口） */
    public static volatile String lastError = "";

    private RawUvc() {}

    /** 反射构造 UsbInterface（AOSP 构造函数是 public 的，但不在 SDK 存根里） */
    private static UsbInterface newInterface(int id, int alt, String name,
                                             int cls, int sub, int proto) throws Exception {
        Constructor<UsbInterface> c = UsbInterface.class.getDeclaredConstructor(
                int.class, int.class, String.class, int.class, int.class, int.class);
        c.setAccessible(true);
        return c.newInstance(id, alt, name, cls, sub, proto);
    }

    /** 反射构造 UsbEndpoint */
    private static UsbEndpoint newEndpoint(int address, int attributes,
                                           int maxPacket, int interval) throws Exception {
        Constructor<UsbEndpoint> c = UsbEndpoint.class.getDeclaredConstructor(
                int.class, int.class, int.class, int.class);
        c.setAccessible(true);
        return c.newInstance(address, attributes, maxPacket, interval);
    }

    /** 把端点挂到接口上：优先 setEndpoints()，失败则直接写 mEndpoints 字段 */
    private static void attach(UsbInterface itf, UsbEndpoint[] eps) throws Exception {
        try {
            Method m = UsbInterface.class.getDeclaredMethod("setEndpoints", Parcelable[].class);
            m.setAccessible(true);
            m.invoke(itf, (Object) eps);
            Log.i(TAG, "setEndpoints() 成功");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "setEndpoints() 不可用(" + t.getClass().getSimpleName() + ")，改写字面量字段");
        }
        Field f = UsbInterface.class.getDeclaredField("mEndpoints");
        f.setAccessible(true);
        f.set(itf, eps);
        Log.i(TAG, "mEndpoints 字段写入成功");
    }

    /**
     * 对「系统没暴露接口」的相机跑一次探针：构造接口/端点 → claim → setInterface → 试读一次。
     *
     * <p>必须在**后台线程**调用：{@code requestWait()} 会阻塞。
     *
     * @param info 从原始描述符解析出的视频流接口信息
     * @return 人类可读的结论（同时会 Log 出来）
     */
    public static String probe(UsbDeviceConnection conn, UvcDescriptors.ItfInfo info) {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[0];
        try {
            if (conn == null) return "conn 为空";
            sb.append("接口").append(info.itfNum).append(" alt").append(info.alt)
              .append(" 端点0x").append(Integer.toHexString(info.epAddr))
              .append(" type=").append(info.epType)
              .append(" maxPacket=").append(info.epMaxPacket).append("；");

            // 1) 反射构造接口 + 端点
            UsbInterface itf = newInterface(info.itfNum, info.alt, "rawVS",
                    0x0E, 0x02, 0x00);
            UsbEndpoint ep = newEndpoint(info.epAddr, info.epAttrs,
                    info.epMaxPacket, info.epInterval);
            attach(itf, new UsbEndpoint[]{ep});
            sb.append("构造OK;");
        } catch (Throwable t) {
            lastError = t.toString();
            Log.e(TAG, "反射构造失败: " + t);
            return sb.append("★反射构造失败 → ").append(t).toString();
        }
        return sb.append("（未 claim）").toString();
    }

    /** claim + setInterface + 读一次（后台线程） */
    public static String claimAndRead(UsbDeviceConnection conn, UvcDescriptors.ItfInfo info) {
        UsbInterface itf = null;
        try {
            itf = newInterface(info.itfNum, info.alt, "rawVS", 0x0E, 0x02, 0x00);
            UsbEndpoint ep = newEndpoint(info.epAddr, info.epAttrs, info.epMaxPacket, info.epInterval);
            attach(itf, new UsbEndpoint[]{ep});

            // 2) claim —— 这是「绕过」的关键：接口号是我们自己解析出来的
            boolean ok = conn.claimInterface(itf, true);
            Log.i(TAG, "claimInterface(" + info.itfNum + ", force=true) => " + ok);
            if (!ok) {
                try { conn.setInterface(itf); } catch (Throwable ignored) {}
                ok = conn.claimInterface(itf, true);
                Log.i(TAG, "重试 claimInterface => " + ok);
            }
            if (!ok) return "★claim 失败（接口 " + info.itfNum + "）";

            // 3) 切换到带端点的 alt（UVC 规范里流端点在 alt>=1）
            if (info.alt != 0) {
                try {
                    conn.setInterface(itf);
                    Log.i(TAG, "setInterface(alt=" + info.alt + ") OK");
                } catch (Throwable t) {
                    Log.w(TAG, "setInterface 失败: " + t);
                }
            }

            // 3.5) ★★ BULK 相机同样必须走 UVC 协商（probe/commit）才会出流：
            //      漏掉这步，固件根本不知道要发帧，bulkTransfer 只会返回 -1。
            //      顺序与 ISO 路径一致：SET_CUR PROBE → GET_CUR 读回 → 用回读值 COMMIT。
            byte[] probe = buildProbe(info, 512);      // 自家固件的 dwMaxPayloadTransferSize=512
            int s1 = conn.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                    UvcProto.SEL_VS_PROBE_CONTROL, info.itfNum, probe, probe.length, 500);
            byte[] back = new byte[UvcProto.PROBE_LEN];
            int g1 = conn.controlTransfer(0xA1 /*class+interface+dev→host*/, 0x81 /*GET_CUR*/,
                    UvcProto.SEL_VS_PROBE_CONTROL, info.itfNum, back, back.length, 500);
            Log.i(TAG, "BULK 协商: probe set=" + s1 + " get=" + g1 + " → " + describeProbe(back));
            byte[] commit = (g1 == UvcProto.PROBE_LEN) ? back : probe;
            int s2 = conn.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                    UvcProto.SEL_VS_COMMIT_CONTROL, info.itfNum, commit, commit.length, 1000);
            Log.i(TAG, "BULK commit=" + s2);

            // 4) 连续试读（固件收到 commit 之后才开始出帧）
            int n;
            if (info.epType == 2) {                     // BULK
                byte[] b = new byte[Math.max(4096, info.epMaxPacket)];
                int total = 0, rounds = 0;
                while (rounds++ < 10 && total <= 0) {
                    int r = conn.bulkTransfer(ep, b, b.length, 1000);
                    if (r > 0) total += r;
                }
                n = total;
                Log.i(TAG, "bulkTransfer 累计 => " + n + " 字节（连续 " + rounds + " 轮）");
            } else {                                     // ISO（UsbRequest 需要后台线程）
                UsbRequest req = new UsbRequest();
                if (!req.initialize(conn, ep)) return "★UsbRequest.initialize 失败";
                ByteBuffer bb = ByteBuffer.allocate(Math.max(1024, info.epMaxPacket * 8));
                if (!req.queue(bb, bb.capacity())) return "★UsbRequest.queue 失败";
                UsbRequest done = conn.requestWait();
                n = (done != null && done.getClientData() != null)
                        ? ((ByteBuffer) done.getClientData()).position() : -1;
                Log.i(TAG, "ISO requestWait => " + n + " 字节");
            }
            String verdict = "claim成功，试读 " + n + " 字节";
            Log.i(TAG, "★★ 绕过探针结论: " + verdict);
            return verdict;
        } catch (Throwable t) {
            lastError = t.toString();
            Log.e(TAG, "绕过探针异常: " + t);
            return "★异常: " + t;
        } finally {
            if (itf != null) {
                try { conn.releaseInterface(itf); } catch (Throwable ignored) {}
            }
        }
    }

    /** 把 26 字节 probe 负载里的关键字段解出来打日志（用于看相机到底认了什么参数） */
    private static String describeProbe(byte[] p) {
         if (p == null || p.length < UvcProto.PROBE_LEN) return "(空)";
         long iv = (p[4] & 0xFFL) | ((p[5] & 0xFFL) << 8) | ((p[6] & 0xFFL) << 16) | ((p[7] & 0xFFL) << 24);
         long fs = (p[18] & 0xFFL) | ((p[19] & 0xFFL) << 8) | ((p[20] & 0xFFL) << 16) | ((p[21] & 0xFFL) << 24);
         long pl = (p[22] & 0xFFL) | ((p[23] & 0xFFL) << 8) | ((p[24] & 0xFFL) << 16) | ((p[25] & 0xFFL) << 24);
         return "fmt" + (p[2] & 0xFF) + "/frame" + (p[3] & 0xFF)
                 + " interval=" + iv + "(" + (iv > 0 ? 10000000L / iv : 0) + "fps)"
                 + " maxFrame=" + fs + " payload=" + pl;
     }

     /** 原始描述符回退的结果（合成出来的接口/端点，供 {@link UvcStreamer} 走系统没暴露的接口） */
     public static final class RawItf {
         public UsbInterface itf;
         public UsbEndpoint endpoint;
         public int itfNum, epAddr, epType, epMaxPacket, alt;
     }

     /**
      * ★★ 应用侧绕过（转发链路入口）：系统视图里找不到可用的视频流端点时，
      * 自己读原始配置描述符，解析出带流端点的 VideoStreaming 接口，
      * 并用反射构造出对应的 {@link UsbInterface}/{@link UsbEndpoint}，
      * 使后续 claim / probe / commit / 读流全部绕开系统的接口模型。
      *
      * <p>实测（Pico 4 / Android 10 + 自家 XIAO 固件）：系统只暴露 3 个接口、缺 VS 接口，
      * 走本回退后 claim 成功、probe/commit 成功、bulkTransfer 读到真实帧数据（4096 字节）。
      *
      * @return 原始描述符里没有可用流接口时返回 null
      */
     public static RawItf buildRawStreamingInterface(android.hardware.usb.UsbDevice dev,
                                                   UsbDeviceConnection conn) {
         if (conn == null) return null;
         try {
             byte[] cfg = UvcDescriptors.readConfig(conn);
             List<UvcDescriptors.ItfInfo> list = UvcDescriptors.findStreamingInterfaces(cfg);
             if (list.isEmpty()) {
                 Log.w(TAG, "原始描述符回退：描述符里没有带流端点的视频流接口");
                 return null;
             }
             // 优先 BULK（Java API 规范支持，不需要 libusb，最稳）；否则取第一个
             UvcDescriptors.ItfInfo pick = null;
             for (UvcDescriptors.ItfInfo i : list) {
                 if (i.epType == 2) { pick = i; break; }
             }
             if (pick == null) pick = list.get(0);

             RawItf r = new RawItf();
             r.itf = newInterface(pick.itfNum, pick.alt, "rawVS", 0x0E, 0x02, 0x00);
             r.endpoint = newEndpoint(pick.epAddr, pick.epAttrs, pick.epMaxPacket, pick.epInterval);
             attach(r.itf, new UsbEndpoint[]{r.endpoint});
             r.itfNum = pick.itfNum;
             r.epAddr = pick.epAddr;
             r.epType = pick.epType;
             r.epMaxPacket = pick.epMaxPacket;
             r.alt = pick.alt;
             Log.i(TAG, "★ 原始描述符回退: 接口" + pick.itfNum + "/alt" + pick.alt
                     + " 端点0x" + Integer.toHexString(pick.epAddr)
                     + " type=" + pick.epType + " maxPacket=" + pick.epMaxPacket
                     + " | " + pick);
             return r;
         } catch (Throwable t) {
             Log.e(TAG, "原始描述符回退失败: " + t);
             return null;
         }
     }

     /** 构造 26 字节 UVC probe/commit 负载（字段布局与 UvcStreamer.buildProbe26 一致）。
     *
     * <p>★ 关键：bFormatIndex / bFrameIndex / dwFrameInterval / dwMaxVideoFrameSize
     * 必须用**该相机描述符里的真实值** —— 填错时 probe 仍可能返回 26，
     * 但 commit 后相机不出流（读不到任何字节）。
     */
    private static byte[] buildProbe(UvcDescriptors.ItfInfo info, int maxPayload) {
        byte[] p = new byte[UvcProto.PROBE_LEN];
        p[2] = (byte) info.fmtIndex;
        p[3] = (byte) info.frameIndex;
        long interval100ns = info.interval100ns > 0 ? info.interval100ns : 166666L;
        p[4] = (byte) (interval100ns & 0xFF);
        p[5] = (byte) ((interval100ns >> 8) & 0xFF);
        p[6] = (byte) ((interval100ns >> 16) & 0xFF);
        p[7] = (byte) ((interval100ns >> 24) & 0xFF);
        int mf = info.maxFrameSize > 0 ? info.maxFrameSize : 320 * 240;
        p[18] = (byte) (mf & 0xFF);
        p[19] = (byte) ((mf >> 8) & 0xFF);
        p[20] = (byte) ((mf >> 16) & 0xFF);
        p[21] = (byte) ((mf >> 24) & 0xFF);
        p[22] = (byte) (maxPayload & 0xFF);
        p[23] = (byte) ((maxPayload >> 8) & 0xFF);
        p[24] = (byte) ((maxPayload >> 16) & 0xFF);
        p[25] = (byte) ((maxPayload >> 24) & 0xFF);
        return p;
    }

    /**
     * ★★ ISO 相机的完整绕过流程：按 maxPacket 从大到小试各 alt，
     * 每个都走「claim → setInterface(alt) → UVC probe/commit → UsbRequest 读一次」，
     * 每一步都打日志 —— 目的是定位到底卡在哪一环（不是靠猜）。
     */
    public static String streamIso(UsbDeviceConnection conn,
                                   List<UvcDescriptors.ItfInfo> cands) {
        if (conn == null || cands == null || cands.isEmpty()) return "无候选端点";
        List<UvcDescriptors.ItfInfo> sorted = new java.util.ArrayList<>(cands);
        // ★ 从**最小包长**开始试：USB 2.0 的 wMaxPacketSize 合法上限是 1024×3=3072，
        //   而这颗廉价相机把「每微帧总负载」直接填进去了（实测 alt4/5/6 = 2848/4896/5120，
        //   后两者非法）—— UsbRequest/URB 初始化很可能拒绝非法包长。
        //   alt1/2/3（128/256/800）都是合法值，先试这些。
        java.util.Collections.sort(sorted, (a, b) -> a.epMaxPacket - b.epMaxPacket);
        int tried = 0;
        for (UvcDescriptors.ItfInfo info : sorted) {
            if (++tried > 6) break;                 // 全部试一遍（6 档）
            UsbInterface itf = null;
            try {
                Log.i(TAG, "—— 尝试 " + info + " ——");
                itf = newInterface(info.itfNum, info.alt, "rawVS", 0x0E, 0x02, 0x00);
                UsbEndpoint ep = newEndpoint(info.epAddr, info.epAttrs,
                        info.epMaxPacket, info.epInterval);
                attach(itf, new UsbEndpoint[]{ep});

                boolean ok = conn.claimInterface(itf, true);
                Log.i(TAG, "claim(" + info.itfNum + ") => " + ok);
                if (!ok) {
                    try { conn.setInterface(itf); } catch (Throwable ignored) {}
                    ok = conn.claimInterface(itf, true);
                    Log.i(TAG, "retry claim => " + ok);
                }
                if (!ok) continue;

                conn.setInterface(itf);
                Log.i(TAG, "setInterface(alt=" + info.alt + ") OK; 端点 addr=0x"
                        + Integer.toHexString(ep.getAddress()) + " type=" + ep.getType()
                        + " dir=" + ep.getDirection() + " maxPacket=" + ep.getMaxPacketSize()
                        + " interval=" + ep.getInterval());

                // UVC 协商：控制传输走设备级 fd，不需要接口对象
                byte[] probe = buildProbe(info, Math.min(info.epMaxPacket, 3000));
                int s1 = conn.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                        UvcProto.SEL_VS_PROBE_CONTROL, info.itfNum, probe, probe.length, 500);
                int s2 = conn.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                        UvcProto.SEL_VS_COMMIT_CONTROL, info.itfNum, probe, probe.length, 1000);
                Log.i(TAG, "probe ret=" + s1 + ", commit ret=" + s2);

                // 读一次
                UsbRequest req = new UsbRequest();
                boolean ini = req.initialize(conn, ep);
                Log.i(TAG, "UsbRequest.initialize => " + ini
                        + (ini ? "" : "（★这一步是当前卡点）"));
                if (!ini) {
                    conn.releaseInterface(itf);
                    continue;
                }
                int sz = Math.max(info.epMaxPacket * 8, 8192);
                sz -= sz % info.epMaxPacket;
                ByteBuffer bb = ByteBuffer.allocate(sz);
                boolean q = req.queue(bb, sz);
                Log.i(TAG, "queue(" + sz + ") => " + q);
                if (!q) {
                    conn.releaseInterface(itf);
                    continue;
                }
                UsbRequest done = conn.requestWait();
                int got = (done != null && done.getClientData() != null)
                        ? ((ByteBuffer) done.getClientData()).position() : -1;
                Log.i(TAG, "requestWait => " + got + " 字节");

                conn.releaseInterface(itf);
                String r = "alt" + info.alt + "/maxPacket" + info.epMaxPacket
                        + " claim✓ probe=" + s1 + " commit=" + s2 + " 读到 " + got + " 字节";
                Log.i(TAG, "★★ ISO 绕过结论: " + r);
                return r;
            } catch (Throwable t) {
                Log.e(TAG, "ISO 绕过异常: " + t);
            } finally {
                if (itf != null) {
                    try { conn.releaseInterface(itf); } catch (Throwable ignored) {}
                }
            }
        }
        return "★所有候选 alt 都失败（详见 UVC_RAW 日志）";
    }

    /**
     * ★★ ISO 相机的 libusb 通路 —— Android API 不支持等时端点，ISO 只能走这里。
     *
     * <p>顺序：wrap fd → libusb claim → libusb setInterface(alt) → UVC probe/commit
     * （控制传输用 Java 侧，已验证可用）→ libusb ISO 读一次。
     */
    public static String streamIsoLibusb(UsbDeviceConnection conn,
                                         List<UvcDescriptors.ItfInfo> cands) {
        if (!RawUsb.AVAILABLE) return "★librawusb 未加载（ABI 或原生构建问题）";
        if (conn == null || cands == null || cands.isEmpty()) return "无候选端点";
        long h = RawUsb.nativeOpen(conn.getFileDescriptor());
        if (h == 0) return "★libusb_wrap_sys_device 失败";
        List<UvcDescriptors.ItfInfo> sorted = new java.util.ArrayList<>(cands);
        // ★ 从小到大：内核按端点 wMaxPacketSize 校验每个 ISO 包长，超了直接 EMSGSIZE
        //   （实测这颗相机的 alt4/5/6 声明了 2848/4896/5120 这种非法包长 → 被内核拒绝）。
        //   alt1/2/3 的 128/256/800 是合法值，先试它们。
        java.util.Collections.sort(sorted, (a, b) -> a.epMaxPacket - b.epMaxPacket);
        try {
            int tried = 0;
            for (UvcDescriptors.ItfInfo info : sorted) {
                if (++tried > 6) break;
                Log.i(TAG, "—— libusb 尝试 " + info + " ——");
                if (!RawUsb.nativeClaim(h, info.itfNum)) continue;

                // ★ UVC 协商，按内核 uvcvideo 的顺序：SET_CUR PROBE → GET_CUR PROBE 读回
                //   → 用**相机回读的值** SET_CUR COMMIT → 最后才切 alt（切 alt 才真正打开等时管道）。
                //   少读回这一步、或先切 alt，实测相机都不出流。
                byte[] probe = buildProbe(info, info.epMaxPacket);
                int s1 = conn.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                        UvcProto.SEL_VS_PROBE_CONTROL, info.itfNum, probe, probe.length, 500);
                byte[] back = new byte[UvcProto.PROBE_LEN];
                // GET_CUR: bmRequestType=0xA1(class+interface+device→host), bRequest=0x81
                int g1 = conn.controlTransfer(0xA1, 0x81,
                        UvcProto.SEL_VS_PROBE_CONTROL, info.itfNum, back, back.length, 500);
                Log.i(TAG, "probe set=" + s1 + " get=" + g1 + " → 相机回读: " + describeProbe(back));
                byte[] commit = (g1 == UvcProto.PROBE_LEN) ? back : probe;   // 优先用相机认可的参数
                int s2 = conn.controlTransfer(UvcProto.BMREQ_HOST_TO_DEV, UvcProto.REQ_SET_CUR,
                        UvcProto.SEL_VS_COMMIT_CONTROL, info.itfNum, commit, commit.length, 1000);
                Log.i(TAG, "commit=" + s2);
                if (!RawUsb.nativeSetAlt(h, info.itfNum, info.alt)) continue;

                // ★★ 关键诊断：alt 到底切没切？
                //   内核 usbfs 的 SETINTERFACE 处理会**吞掉 usb_set_interface 的错误、永远返回 0**，
                //   所以 setAlt 返回 0 不能证明 alt 生效。用 USB 标准请求 GET_INTERFACE
                //   （bmRequestType=0x81, bRequest=0x0A, wIndex=接口号）直接问设备当前 alt。
                if (conn != null) {
                    byte[] altBuf = new byte[1];
                    int gotAlt = conn.controlTransfer(0x81, 0x0A, 0, info.itfNum, altBuf, 1, 500);
                    int curAlt = (gotAlt == 1) ? (altBuf[0] & 0xFF) : -1;
                    Log.i(TAG, "★ GET_INTERFACE(接口" + info.itfNum + ") => ret=" + gotAlt
                            + " 设备当前 alt=" + curAlt + "（期望 " + info.alt + "）"
                            + (curAlt == info.alt ? " ✓ alt 已生效" : " ✗ alt 没切成功！"));
                }

                // ★ 每包长度上限 1023：内核按端点 maxpacket 校验（全速上限 1023、
                //   高速 1024×mult），取小值最安全；帧虽不完整，但足以验证通路。
                int perPacket = Math.min(info.epMaxPacket, 1023);
                int packets = 64;                    // 单个 URB 覆盖 ~8ms（64×125µs），提高命中率
                int len = perPacket * packets;
                byte[] buf = new byte[len];
                // ★★ 连续读多轮：廉价 UVC 相机的 MJPEG 是**按帧突发**发送的（例：153600 字节
                //    在 ~4ms 内发完，然后空闲 ~29ms）。只发一个 2ms 的 URB 极可能正好落在
                //    空闲窗口里 → 读到 0 字节、误判为"不通"。这里连续读到有数据为止。
                int total = 0, rounds = 0;
                while (rounds++ < 40 && total == 0) {
                    int got = RawUsb.nativeIsoRead(h, info.epAddr, buf, len, packets, 1500);
                    if (got > 0) total += got;
                }
                Log.i(TAG, "★★ libusb ISO 结论: alt" + info.alt + "/maxPacket" + info.epMaxPacket
                        + " claim✓ setAlt✓ probe=" + s1 + " commit=" + s2
                        + " → 累计读到 " + total + " 字节（连续 " + rounds + " 轮）");
                if (total > 0) {
                    return "alt" + info.alt + "/maxPacket" + info.epMaxPacket
                            + " probe=" + s1 + " commit=" + s2 + " ✅读到 " + total + " 字节";
                }
            }
            return "★所有候选 alt 都读不到数据（详见 UVC_RAW_JNI 日志）";
        } finally {
            RawUsb.nativeClose(h);
        }
    }
}
