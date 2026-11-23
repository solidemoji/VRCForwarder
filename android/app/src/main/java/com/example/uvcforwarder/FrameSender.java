package com.example.uvcforwarder;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;

/**
 * 把完整 JPEG 帧切成 ≤1200 字节的 UDP 分片发送，带帧序号与分片索引。
 * 接收端：缺任何一片即丢弃整帧（眼追 IR 帧逐帧独立，宁可丢帧绝不花屏残影）。
 */
public class FrameSender {

    private static final String TAG = "UVC_SENDER";

    public interface Listener {
        /** 统计回调：sentFrames 已发送帧数, sentFragments 已发送分片数, queueDepth 待发送帧数 */
        void onNetworkStats(long sentFrames, long sentFragments, int queueDepth);
    }

    private static class Frame {
        final long seq;
        final byte[] jpeg;
        Frame(long seq, byte[] jpeg) { this.seq = seq; this.jpeg = jpeg; }
    }

    private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>(4);
    private final AtomicLong seqCounter = new AtomicLong(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket socket;
    private InetAddress target;
    private int targetPort;
    private Thread sendThread;
    private final Listener listener;
    /** 本路 stream-id（0~MAX_STREAMS-1），写入每个分片头 */
    private volatile int streamId = 0;

    /** 已发送帧数（服务用它判断这一路是否真的在出流） */
    public long getSentFrames() {
        return statFrames;
    }

    private long statFrames = 0;
    private long statFragments = 0;

    public FrameSender(Listener listener) {
        this.listener = listener;
    }

    /**
     * 接收端下发规格的回调（反向控制通道）。
     * 完全独立于视频发送链路，不影响原有识别/发送逻辑。
     */
    public interface SpecListener {
        void onRemoteSpec(int sid, int width, int height, int fps, String fmt);
    }

    private volatile SpecListener specListener;
    private Thread recvThread;

    public void setSpecListener(SpecListener l) {
        this.specListener = l;
    }

    /** 监听接收端回发的规格包（与视频同端口，包体为 "UC" + JSON）。 */
    private void recvLoop() {
        byte[] buf = new byte[2048];
        while (running.get()) {
            DatagramSocket s = socket;
            if (s == null || s.isClosed()) break;
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                int len = p.getLength();
                if (len < 3 || buf[0] != 'U' || buf[1] != 'C') continue;
                String json = new String(buf, 2, len - 2, "UTF-8");
                JSONObject o = new JSONObject(json);
                if (o.optInt("sid", streamId) != streamId) continue;   // 只管自己这路
                int w = o.optInt("w", 0), h = o.optInt("h", 0), fps = o.optInt("fps", 0);
                String fmt = o.optString("fmt", "MJPG");
                if (w <= 0 || h <= 0) continue;
                Log.i(TAG, "收到远端规格 s" + streamId + ": " + w + "x" + h + "@" + fps + " " + fmt);
                SpecListener l = specListener;
                if (l != null) l.onRemoteSpec(streamId, w, h, fps, fmt);
            } catch (SocketTimeoutException te) {
                // 正常：超时后继续等
            } catch (Exception e) {
                DatagramSocket cur = socket;
                if (!running.get() || cur == null || cur.isClosed()) break;
            }
        }
    }

    /**
     * 发送一路元数据包（sid=255 控制通道，不参与视频流）：
     * 载荷为 UTF-8 JSON，如 {"sid":0,"name":"XIAO ESP32-S3","kind":"BULK"}。
     * 接收端据此把数字流号映射为可读摄像头名称。
     */
    public synchronized void sendMeta(String json) {
        try {
            if (socket == null || target == null) return;
            byte[] body = json.getBytes("UTF-8");
            if (body.length > UvcProto.NET_PAYLOAD_MAX) return;
            byte[] pkt = new byte[UvcProto.NET_HEADER_LEN + body.length];
            pkt[0] = 'U';
            pkt[1] = 'V';
            pkt[2] = (byte) UvcProto.NET_SID_META;   // 255
            System.arraycopy(body, 0, pkt, UvcProto.NET_HEADER_LEN, body.length);
            socket.send(new DatagramPacket(pkt, pkt.length, target, targetPort));
            Log.i(TAG, "meta 已发送 s" + streamId + ": " + json);
        } catch (Exception e) {
            Log.w(TAG, "sendMeta 失败: " + e);
        }
    }

    /** 设置目标并启动发送线程（可重复调用以改目标） */
    public synchronized void start(String ip, int port) throws SocketException, UnknownHostException {
        stop();
        this.target = InetAddress.getByName(ip);
        this.targetPort = port;
        this.socket = new DatagramSocket();
        try {
            this.socket.setSoTimeout(800);   // 规格监听线程靠超时轮询退出
        } catch (SocketException ignored) {}
        running.set(true);
        sendThread = new Thread(this::sendLoop, "frame-sender");
        sendThread.start();
        recvThread = new Thread(this::recvLoop, "spec-recv");
        recvThread.start();
        Log.i(TAG, "sender started -> " + ip + ":" + port);
    }

    public synchronized void stop() {
        running.set(false);
        if (sendThread != null) {
            sendThread.interrupt(); // 解除 queue.take() 阻塞
            try { sendThread.join(500); } catch (InterruptedException ignored) {}
            sendThread = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (recvThread != null) {
            try { recvThread.join(500); } catch (InterruptedException ignored) {}
            recvThread = null;
        }
        queue.clear();
    }

    /** 设置本路 stream-id，须在 start() 前调用 */
    public synchronized void setStreamId(int id) {
        this.streamId = id & 0xFF;
    }

    /** 入队一帧 JPEG；队列满则丢帧（网络跟不上时优先保实时性） */
    public void submit(byte[] jpeg) {
        if (!running.get()) return;
        long seq = seqCounter.getAndIncrement();
        if (!queue.offer(new Frame(seq, jpeg))) {
            Log.w(TAG, "发送队列满，丢帧 seq=" + seq);
        }
    }

    private void sendLoop() {
        byte[] payload = new byte[UvcProto.NET_HEADER_LEN + UvcProto.NET_PAYLOAD_MAX];
        while (running.get()) {
            try {
                Frame f = queue.take();
                if (f.jpeg.length > 60 * 1024) {
                    // 超过约 60KB 的帧会切 50+ 片，提示但不中断
                    Log.w(TAG, "帧偏大: " + f.jpeg.length + "B, 分片数较多");
                }
                int fragCount = (f.jpeg.length + UvcProto.NET_PAYLOAD_MAX - 1) / UvcProto.NET_PAYLOAD_MAX;
                // 序号回绕保护：约 40 亿帧后才回绕，单次会话不可能达到
                byte[] seqB = intToBytes((int) f.seq);
                for (int i = 0; i < fragCount; i++) {
                    int off = i * UvcProto.NET_PAYLOAD_MAX;
                    int len = Math.min(UvcProto.NET_PAYLOAD_MAX, f.jpeg.length - off);
                    payload[0] = UvcProto.NET_MAGIC[0];
                    payload[1] = UvcProto.NET_MAGIC[1];
                    payload[2] = (byte) streamId;                 // v2: stream-id
                    System.arraycopy(seqB, 0, payload, 3, 4);
                    payload[7] = (byte) (i & 0xFF);
                    payload[8] = (byte) ((i >> 8) & 0xFF);
                    payload[9] = (byte) (fragCount & 0xFF);
                    payload[10] = (byte) ((fragCount >> 8) & 0xFF);
                    System.arraycopy(f.jpeg, off, payload, UvcProto.NET_HEADER_LEN, len);
                    DatagramPacket pkt = new DatagramPacket(payload, UvcProto.NET_HEADER_LEN + len, target, targetPort);
                    socket.send(pkt);
                    statFragments++;
                }
                statFrames++;
                if (listener != null) listener.onNetworkStats(statFrames, statFragments, queue.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "UDP 发送失败: " + e.getMessage());
                    try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                }
            }
        }
        Log.i(TAG, "sender stopped");
    }

    private static byte[] intToBytes(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }
}
