package com.example.uvcforwarder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 多路前台服务：会话槽管理，全部 USB 操作在专用 HandlerThread 串行执行。
 *  - 主线程(广播/命令回调)只投递任务，绝不执行 USB 打开/释放（防 ANR）；
 *  - 每摄像头一个槽(sender+收流会话)，自动分类 BULK/ISO；
 *  - 拔出：只停对应槽，其余路继续；插入：自动增量纳管已授权设备。
 */
public class UsbForwardService extends Service {

    private static final String TAG = "UVC_SERVICE";
    public static final String CHANNEL_ID = "uvc_forward_channel";
    private static final int NOTIFY_ID = 1;

    public static final String EXTRA_IP = "pc_ip";
    public static final String EXTRA_PORT = "pc_port";
    /** Service 内部自动授权请求的广播 action */
    private static final String ACTION_SVC_PERM = "com.example.uvcforwarder.SVC_USB_PERMISSION";
    /** 正在等待自动授权弹窗结果的设备名（去重，防 attach 风暴重复弹窗） */
    private final java.util.Set<String> pendingPerms =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 一个摄像头会话槽 */
    private static class Slot {
        int sid;
        UsbDevice device;
        UsbDeviceConnection conn;   // 用于读 UVC 描述符名称(独立连接)
        UvcStreamer.UvcKind kind;
        FrameSender sender;
        UvcStreamer bulk;
        IsoUvcSession iso;
        boolean active = false;
    }

    private UsbManager usbManager;
    private WifiManager.WifiLock wifiLock;
    private final Slot[] slots = new Slot[UvcProto.MAX_STREAMS];
    private volatile String targetIp;
    private volatile int targetPort;
    private volatile boolean stopped = false;
    private volatile boolean repeaterStarted = false;

    /** 槽操作专用线程：所有 USB 启停都在这里串行跑 */
    private HandlerThread slotThread;
    private Handler slotHandler;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Log.w(TAG, "USB 拔出: " + (dev != null ? dev.getDeviceName() : "?"));
                // 只投递任务，主线程不做任何 USB 操作
                if (dev != null) postSlotOp(() -> removeDeviceSlotOnThread(dev));
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Log.i(TAG, "USB 插入: " + (dev != null ? dev.getDeviceName() : "?"));
                if (dev != null) postSlotOp(UsbForwardService.this::addMissingDevicesOnThread);
            } else if (ACTION_SVC_PERM.equals(action)) {
                // 自动授权弹窗结果：允许则重扫纳管，拒绝则提示
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                String name = dev != null ? dev.getDeviceName() : null;
                if (name != null) pendingPerms.remove(name);
                if (granted) {
                    Log.i(TAG, "自动授权成功: " + name);
                    postSlotOp(UsbForwardService.this::addMissingDevicesOnThread);
                } else {
                    Log.w(TAG, "自动授权被拒绝: " + name);
                    broadcast(getString(R.string.svc_perm_denied, name));
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        createChannel();
        startForeground(NOTIFY_ID, buildNotification(getString(R.string.notify_starting)));
        slotThread = new HandlerThread("uvc-slots");
        slotThread.start();
        slotHandler = new Handler(slotThread.getLooper());
        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(ACTION_SVC_PERM);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        targetIp = intent.getStringExtra(EXTRA_IP);
        targetPort = intent.getIntExtra(EXTRA_PORT, UvcProto.NET_PORT_DEFAULT);

        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "uvc_forward_lock");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        // 单设备命令（设备列表 UI：单独转发/停止某一路）
        String devName = intent.getStringExtra(UvcProto.EXTRA_DEVICE_NAME);
        String devAction = intent.getStringExtra(UvcProto.EXTRA_DEVICE_ACTION);
        if (devName != null && devAction != null) {
            if (UvcProto.DEVICE_ACTION_START.equals(devAction)) {
                postSlotOp(() -> addOneDeviceOnThread(devName));
            } else if (UvcProto.DEVICE_ACTION_STOP.equals(devAction)) {
                postSlotOp(() -> removeDeviceByNameOnThread(devName));
            }
            return START_STICKY;
        }

        // 首次启动或全量重扫都走同一串行任务
        postSlotOp(() -> {
            addMissingDevicesOnThread();
            updateNotification(forwardingText());
            replayDeviceStates();
        });
        // meta 周期重发线程（daemon）：保证 PC 端重启后能拿到名称/稳定 key
        if (!repeaterStarted) {
            repeaterStarted = true;
            Thread repeater = new Thread(this::metaRepeaterLoop, "uvc-meta-repeater");
            repeater.setDaemon(true);
            repeater.start();
        }
        return START_STICKY;
    }
    private void replayDeviceStates() {
        for (Slot s : slots) {
            if (s != null && s.active && s.device != null) {
                deviceStateBroadcast(s.device.getDeviceName(), true);
            }
        }
    }

    /** 周期重发所有活动槽的 meta：PC 端随时重启/新开都能拿到名称+稳定 key URL */
    private void metaRepeaterLoop() {
        while (!stopped) {
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                break;
            }
            try {
                for (Slot s : slots) {
                    if (s != null && s.active && s.device != null) {
                        sendMetaForDevice(s.device, s.sid);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void postSlotOp(Runnable r) {
        Handler h = slotHandler;
        if (h != null) h.post(r);
    }

    private int activeCount() {
        int n = 0;
        for (Slot s : slots) if (s != null && s.active) n++;
        return n;
    }

    /** 槽线程内：增量纳管"已授权且未纳管"的摄像头 */
    private void addMissingDevicesOnThread() {
        List<UsbDevice> cams = new ArrayList<>();
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (UvcStreamer.classify(d) != UvcStreamer.UvcKind.NONE) cams.add(d);
        }
        // 按稳定身份排序后再分配槽位：同套设备(VID:PID+序列号)无论插入顺序，
        // 都得到相同的槽位/stream-id，避免序号漂移（左右眼不互换）
        cams.sort((a, b) -> UvcStreamer.deviceKey(a).compareTo(UvcStreamer.deviceKey(b)));
        boolean anyMissingAuth = false;
        for (UsbDevice dev : cams) {
            if (findSlotByDevice(dev) != null) continue; // 已纳管
            if (!usbManager.hasPermission(dev)) {
                // 无权限：自动弹授权（免驱设备无序列号，重插后权限不保留；
                // 等待用户允许后自动接入，实现"插回即恢复"）
                anyMissingAuth = true;
                requestSvcPermission(dev);
                continue;
            }
            int sid = freeSlotId();
            if (sid < 0) {
                Log.w(TAG, "槽位已满，忽略 " + dev.getDeviceName());
                broadcast(getString(R.string.svc_limit_reached, UvcProto.MAX_STREAMS));
                break;
            }
            startSlotOnThread(sid, dev);
        }
        if (anyMissingAuth && !hasActive()) {
            // 有设备但未授权：若完全没有活动路数，提示用户留意授权弹窗
            broadcast(getString(R.string.svc_please_allow));
        }
    }

    /** 槽线程内：为设备发起一次自动授权请求（同一设备去重，防重复弹窗） */
    private void requestSvcPermission(UsbDevice dev) {
        String name = dev.getDeviceName();
        if (!pendingPerms.add(name)) return; // 已有未决请求
        try {
            Intent intent = new Intent(ACTION_SVC_PERM);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 1, intent, flags);
            Log.i(TAG, "自动请求授权: " + name);
            usbManager.requestPermission(dev, pi);
        } catch (Exception e) {
            Log.e(TAG, "自动授权请求失败: " + name, e);
            pendingPerms.remove(name);
        }
    }

    /** 槽线程内：按设备名纳管单一路（设备列表 UI 单独转发用） */
    private void addOneDeviceOnThread(String devName) {
        UsbDevice found = null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getDeviceName().equals(devName)
                    && UvcStreamer.classify(d) != UvcStreamer.UvcKind.NONE) {
                found = d;
                break;
            }
        }
        if (found == null) {
            broadcast(getString(R.string.svc_device_offline, devName));
            return;
        }
        if (findSlotByDevice(found) != null) {
            broadcast(getString(R.string.svc_already_forwarding, devName));
            return;
        }
        if (!usbManager.hasPermission(found)) {
            broadcast(getString(R.string.svc_no_permission, devName));
            return;
        }
        int sid = freeSlotId();
        if (sid < 0) {
            broadcast(getString(R.string.svc_limit_reached_short, UvcProto.MAX_STREAMS));
            return;
        }
        startSlotOnThread(sid, found);
    }

    /** 槽线程内：按设备名停止单一路 */
    private void removeDeviceByNameOnThread(String devName) {
        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            if (s != null && s.active && s.device != null
                    && s.device.getDeviceName().equals(devName)) {
                stopAndFreeSlotOnThread(i, false);
                broadcast(getString(R.string.svc_stopped_device, devName));
                if (!hasActive()) {
                    Log.i(TAG, "单设备停止后无活动会话，服务自停");
                    stopSelf();
                }
                return;
            }
        }
        broadcast(getString(R.string.svc_not_forwarding, devName));
    }

    private boolean hasActive() {
        for (Slot s : slots) if (s != null && s.active) return true;
        return false;
    }

    /** 槽线程内：指定槽启动一路会话 */
    private void startSlotOnThread(int sid, UsbDevice dev) {
        final UvcStreamer.UvcKind kind = UvcStreamer.classify(dev);
        final String label = "路" + (sid + 1) + "[" + dev.getDeviceName() + "]";

        FrameSender fs = new FrameSender((sent, frags, qd) ->
                Log.d(TAG, "net s" + sid + ": frames=" + sent));
        fs.setStreamId(sid);
        try {
            fs.start(targetIp, targetPort);
        } catch (Exception e) {
            Log.e(TAG, "sender 启动失败", e);
            broadcast(label + ": 网络启动失败 " + e.getMessage());
            return;
        }

        Slot slot = new Slot();
        slot.sid = sid;
        slot.device = dev;
        slot.kind = kind;
        slot.sender = fs;
        // 独立连接：仅用于读 UVC 描述符里的摄像头名称(读名不占用流会话的连接)
        try { slot.conn = usbManager.openDevice(dev); } catch (Exception ignored) {}
        try {
            if (kind == UvcStreamer.UvcKind.BULK) {
                UvcStreamer st = new UvcStreamer(usbManager, new UvcStreamer.Listener() {
                    @Override
                    public void onFrame(byte[] jpeg) { fs.submit(jpeg); }

                    @Override
                    public void onState(String text) {
                        broadcast(label + "(BULK): " + text);
                    }

                    @Override
                    public void onStats(float fps, int avgBytes) {
                        updateNotification(getString(R.string.notify_forwarding_stats,
                                activeCount(), label,
                                String.format(java.util.Locale.US, "%.0f fps", fps)));
                    }
                });
                st.start(dev, UvcProto.FRAME_INTERVAL_60);
                slot.bulk = st;
            } else {
                IsoUvcSession iso = new IsoUvcSession(this, dev, fs,
                        new IsoUvcSession.Listener() {
                            @Override
                            public void onFrame(byte[] jpeg) { fs.submit(jpeg); }

                            @Override
                            public void onState(String text) {
                                broadcast(label + "(ISO): " + text);
                            }
                        });
                iso.start();
                slot.iso = iso;
            }
        } catch (Exception e) {
            Log.e(TAG, "会话启动失败", e);
            broadcast(label + ": 启动失败 " + e.getMessage());
            try { fs.stop(); } catch (Exception ignored) {}
            return;
        }
        slots[sid] = slot;
        slot.active = true;
        updateNotification(forwardingText());
        broadcast(getString(R.string.svc_joined, label, kind));
        deviceStateBroadcast(dev.getDeviceName(), true);
        // 发送元数据（sid=255 控制包）：接收端显示摄像头名称 + 稳定 key(用于固定 URL)
        sendMetaForDevice(dev, sid);
    }

    /** 组装并发送某设备的 meta 包（槽线程/周期线程均可调用，sendMeta 自带线程安全） */
    private void sendMetaForDevice(UsbDevice dev, int sid) {
        try {
            Slot s = findSlotByDevice(dev);
            if (s == null || s.sender == null) return;
            String key = UvcStreamer.deviceKey(dev);
            s.sender.sendMeta("{\"sid\":" + sid + ",\"name\":\""
                    + UvcStreamer.jsonEscape(UvcStreamer.deviceDisplayName(dev, s.conn))
                    + "\",\"kind\":\"" + UvcStreamer.classify(dev)
                    + "\",\"key\":\"" + UvcStreamer.jsonEscape(key) + "\"}");
        } catch (Exception ignored) {}
    }

    /** 槽线程内：拔出设备只停对应槽 */
    private void removeDeviceSlotOnThread(UsbDevice dev) {
        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            if (s != null && s.active && s.device != null && s.device.equals(dev)) {
                stopAndFreeSlotOnThread(i, true);  // 设备已拔出：跳过与设备的 native 交互
                broadcast(getString(R.string.svc_detached));
                if (!hasActive()) {
                    Log.i(TAG, "无活动会话，服务自停");
                    // stopSelf 线程安全，可在槽线程直接调用
                    stopSelf();
                }
                return;
            }
        }
    }

    /** 槽线程内：停止并释放指定槽（deviceGone=true 表示设备已拔出，跳过原生停流交互） */
    private void stopAndFreeSlotOnThread(int i, boolean deviceGone) {
        Slot s = slots[i];
        if (s == null) return;
        slots[i] = null;
        String devName = s.device != null ? s.device.getDeviceName() : null;
        if (s.bulk != null) { try { s.bulk.stop(); } catch (Exception ignored) {} }
        if (s.iso != null) {
            try {
                if (deviceGone) s.iso.stopForDetach(); else s.iso.stop();
            } catch (Exception ignored) {}
        }
        if (s.sender != null) { try { s.sender.stop(); } catch (Exception ignored) {} }
        // 关闭读名用的独立连接(拔出时设备已不在，直接吞异常)
        if (s.conn != null) { try { s.conn.close(); } catch (Exception ignored) {} }
        s.active = false;
        updateNotification(forwardingText());
        if (devName != null) deviceStateBroadcast(devName, false);
    }

    private Slot findSlotByDevice(UsbDevice dev) {
        for (Slot s : slots) {
            if (s != null && s.active && s.device != null && s.device.equals(dev)) return s;
        }
        return null;
    }

    private int freeSlotId() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null || !slots[i].active) return i;
        }
        return -1;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        Handler h = slotHandler;
        slotHandler = null;
        if (h != null) {
            // 在槽线程完成收尾后再退出线程
            h.post(() -> {
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i] != null) stopAndFreeSlotOnThread(i, false);
                }
                if (slotThread != null) slotThread.quitSafely();
            });
        }
        if (wifiLock != null && wifiLock.isHeld()) { try { wifiLock.release(); } catch (Exception ignored) {} wifiLock = null; }
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        broadcast(getString(R.string.svc_all_stopped));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.notify_title))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    /** 统一的"转发中 N 路 → ip:port"通知文案 */
    private String forwardingText() {
        return getString(R.string.notify_forwarding, activeCount(),
                targetIp, targetPort);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, buildNotification(text));
    }

    private void broadcast(String text) {
        if (text != null) {
            Intent i = new Intent(UvcProto.ACTION_STATUS);
            i.setPackage(getPackageName());
            i.putExtra(UvcProto.EXTRA_STATUS, text);
            sendBroadcast(i);
        }
    }

    /** 设备级活动状态广播（MainActivity 设备列表同步按钮用） */
    private void deviceStateBroadcast(String devName, boolean active) {
        Intent i = new Intent(UvcProto.ACTION_DEVICE_STATE);
        i.setPackage(getPackageName());
        i.putExtra(UvcProto.EXTRA_DEVICE_NAME, devName);
        i.putExtra(UvcProto.EXTRA_DEVICE_ACTIVE, active);
        sendBroadcast(i);
    }
}
