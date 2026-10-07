package com.example.uvcforwarder;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * 主界面：设置 PC 的 IP/端口 -> 插入 UVC 摄像头 -> 开始转发。
 * 可选"自动搜索电脑"：向局域网广播发现 PC 端 UVCReceiver（须先启动 receiver）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "UVC_MAIN";
    private static final String ACTION_USB_PERMISSION = "com.example.uvcforwarder.USB_PERMISSION";
    private static final int REQ_PERMISSIONS = 1001;
    private static final String PREF = "uvc_pref";

    private UsbManager usbManager;
    private Button btnToggle, btnScan;
    private EditText etIp, etPort;
    private TextView tvStatus, tvStats;
    private LinearLayout deviceList;
    private boolean serviceRunning = false;
    private final Queue<UsbDevice> pendingAuth = new ArrayDeque<>();
    /** 单设备授权流程中发起请求的设备名（非全量队列流程时非 null） */
    private String pendingSingleName = null;
    /** 当前在转发的设备名集合（由 Service 的 DEVICE_STATE 广播同步） */
    private final Set<String> activeDevices = new HashSet<>();
    /** 各活动路**实际生效**的视频规格文本（设备名 → "MJPEG 640x480@120"） */
    private final java.util.Map<String, String> deviceSpec = new java.util.HashMap<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (pendingSingleName != null && dev != null
                        && dev.getDeviceName().equals(pendingSingleName)) {
                    // 单设备授权流程
                    pendingSingleName = null;
                    if (granted) {
                        startDeviceAction(dev, UvcProto.DEVICE_ACTION_START);
                    } else {
                        tvStatus.setText(getString(R.string.msg_perm_denied_device,
                                dev.getDeviceName()));
                    }
                } else if (granted) {
                    requestNext(); // 还有设备待授权则继续弹窗，全部完成即启动
                } else {
                    tvStatus.setText(R.string.msg_perm_denied_some);
                    requestNext();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                tvStatus.setText(R.string.msg_usb_attached);
                btnToggle.setEnabled(true);
                refreshDeviceList();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // 不再停整个服务：UsbForwardService 已支持单路热拔管理(只停被拔那路)
                tvStatus.setText(R.string.msg_usb_detached);
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null) {
                    activeDevices.remove(dev.getDeviceName());
                }
                refreshDeviceList();
            } else if (UvcProto.ACTION_DEVICE_STATE.equals(action)) {
                String name = intent.getStringExtra(UvcProto.EXTRA_DEVICE_NAME);
                if (name != null) {
                    boolean act = intent.getBooleanExtra(UvcProto.EXTRA_DEVICE_ACTIVE, false);
                    if (act) {
                        activeDevices.add(name);
                        String spec = intent.getStringExtra(UvcProto.EXTRA_DEVICE_SPEC);
                        if (!TextUtils.isEmpty(spec)) {
                            deviceSpec.put(name, spec);
                        }
                    } else {
                        activeDevices.remove(name);
                        deviceSpec.remove(name);
                    }
                    refreshDeviceList();
                }
            } else if (UvcProto.ACTION_STATUS.equals(action)) {
                String s = intent.getStringExtra(UvcProto.EXTRA_STATUS);
                if (!TextUtils.isEmpty(s)) {
                    tvStatus.setText(s);
                    tvStatus.append("\n" + getString(R.string.stat_prefix) + buildStatsText());
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        etIp = findViewById(R.id.et_ip);
        etPort = findViewById(R.id.et_port);
        tvStatus = findViewById(R.id.tv_status);
        tvStats = findViewById(R.id.tv_stats);
        btnToggle = findViewById(R.id.btn_toggle);
        btnScan = findViewById(R.id.btn_scan);
        deviceList = findViewById(R.id.device_list);

        // 版本号：从 build.gradle 的 versionName 读取（发布/排障时可见）
        TextView tvVersion = findViewById(R.id.tv_version);
        tvVersion.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        etIp.setText(sp.getString("ip", ""));
        etPort.setText(String.valueOf(sp.getInt("port", UvcProto.NET_PORT_DEFAULT)));

        btnToggle.setOnClickListener(v -> {
            if (serviceRunning) {
                stopServiceSafe();
            } else {
                if (!TextUtils.isEmpty(etIp.getText())) {
                    savePrefs();
                    ensurePermissionsThenStart();
                } else {
                    tvStatus.setText(R.string.msg_need_ip);
                }
            }
        });

        btnScan.setOnClickListener(v -> discoverPc());
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UvcProto.ACTION_STATUS);
        f.addAction(UvcProto.ACTION_DEVICE_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        refreshUi();
        refreshDeviceList();
        // 若服务已在后台运行，触发一次全量状态回放以同步设备列表按钮
        if (serviceRunning) {
            try {
                startService(new Intent(this, UsbForwardService.class)
                        .putExtra(UsbForwardService.EXTRA_IP, etIp.getText().toString().trim())
                        .putExtra(UsbForwardService.EXTRA_PORT, parseIntSafe(
                                etPort.getText().toString(), UvcProto.NET_PORT_DEFAULT)));
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServiceSafe();
    }

    private void savePrefs() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("ip", etIp.getText().toString().trim())
                .putInt("port", parseIntSafe(etPort.getText().toString(), UvcProto.NET_PORT_DEFAULT))
                .apply();
    }

    private void refreshUi() {
        serviceRunning = isServiceRunning();
        btnToggle.setText(serviceRunning ? R.string.btn_stop : R.string.btn_start);
        if (serviceRunning) {
            tvStatus.setText(R.string.status_running);
        }
    }

    private boolean isServiceRunning() {
        try {
            return getSystemService(Context.ACTIVITY_SERVICE) != null
                    && ((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE))
                    .getRunningServices(Integer.MAX_VALUE).stream()
                    .anyMatch(s -> s.service.getClassName().equals(UsbForwardService.class.getName()));
        } catch (Exception e) {
            return false;
        }
    }

    /** 找出所有含 UVC 视频流接口的已插入设备（bulk XIAO 与 ISO 免驱都算） */
    private List<UsbDevice> findCameras() {
        List<UsbDevice> out = new ArrayList<>();
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (UvcStreamer.classify(d) != UvcStreamer.UvcKind.NONE) {
                out.add(d);
                continue;
            }
            // ★ 不再静默丢弃：系统一个接口都没给出来（接口数=0）时，自己读原始描述符
            //   判断它到底是不是相机。实测 Pico 4 / Android 10 上部分 UVC 相机（含带麦克风的
            //   复合相机）会让系统解析器抛 IllegalArgumentException → 接口数 0 →
            //   旧逻辑直接让它从列表消失，用户只会以为 App 坏了。
            // ★ 注意：这里**绝不能**再要求 usbManager.hasPermission ——
            //   设备的 USB 权限正是对本函数返回的列表申请的（见 ensureUsbPermission()）。
            //   要求"已授权"会造成死锁：0 接口的相机永远进不了列表 ⇒ 永不申请权限
            //   ⇒ 永远没权限 ⇒ 永远进不了列表。现象＝插上相机后列表里什么都没有。
            if (maybeUnparsedCamera(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 0 接口的设备：可能是"系统没解析出接口"的相机。
     * （Pico 4 / Android 10 实测：部分相机的描述符会让系统解析器抛
     * {@code IllegalArgumentException} → 系统给出的接口数为 0，任何 App 都拿不到它。）
     *
     * <p>⚠️ 本函数**不要求先有 USB 权限** —— 权限正是对本函数返回的设备申请的。
     * 未授权时先按"疑似"收下（让权限流程覆盖到它），授权后再读原始描述符确认；
     * 已授权但确认不是相机（例如某些复合扩展坞）则排除。
     */
    private boolean maybeUnparsedCamera(UsbDevice d) {
        if (d.getInterfaceCount() != 0) return false;
        int cls = d.getDeviceClass();              // 0=按接口定义(复合设备) / 0xEF=杂类(IAD)
        if (cls != 0 && cls != 0xEF) return false; // 排除 hub(9)/HID(3)/存储(8) 等
        if (!usbManager.hasPermission(d)) {
            Log.i(TAG, "0 接口设备（疑似相机，待授权）: " + d.getDeviceName());
            return true;
        }
        boolean ok = rawHasVideo(d);
        Log.i(TAG, "0 接口设备 " + d.getDeviceName()
                + " 原始描述符含视频接口=" + ok + "（已授权，据此决定是否列出）");
        if (ok && probedRaw.add(d.getDeviceName())) {   // 每台设备只探一次
            tryBypass(d);
        }
        return ok;
    }

    /** 已「绕过探针」过的设备名，避免每次刷新都重复 probe */
    private final java.util.Set<String> probedRaw = new java.util.HashSet<>();

    /**
     * ★ 对照实验开关：对本机**系统已正常识别**的相机也跑一次 libusb 绕过探针。
     *
     * <p>目的：验证「libusb 等时通路本身」是否成立 —— 拿一台已知能用的相机
     * （例如 OV9281）走同一条代码路径，如果读到数据，说明通路没问题、
     * 问题出在特定相机的内核支持上；如果也读不到，说明问题在我们的实现里。
     * 验证完请改回 false。
     */
    private static final boolean DEBUG_BYPASS_ALL_CAMERAS = false;

    /**
     * ★ 实验：把相机交给**已验证能读 ISO 的 saki/libuvc 原生栈**试一次。
     *
     * <p>意义：OV9281 在这台 Pico 4 上经这条通路跑到 120fps，说明「设备 + 内核 + 原生 ISO 读取」
     * 这条链路本身是通的。把它指向 GC0308：
     * <ul>
     *   <li>读到帧 → 问题在**我们自己那条 libusb 实现**里（可修）</li>
     *   <li>也读不到 → 问题在**相机/内核**这一层（选型时要避开这类模组）</li>
     * </ul>
     *
     * <p>与 {@link IsoUvcSession} 的区别：不依赖系统枚举出的接口（saki native 自己按 fd 解析），
     * 所以 0 接口设备也能试；也不读能力表、不做规格选择，只问一件事：**有没有字节出来**。
     */
    private void testSakiNativeOnDevice(UsbDevice d) {
        new Thread(() -> {
            com.serenegiant.usb.USBMonitor mon = null;
            try {
                Log.i(TAG, "saki 实验: 开始，设备 " + d.getDeviceName());
                // 注意：USBMonitor 构造时会注册广播接收器，监听器不能传 null（插拔事件会 NPE）
                mon = new com.serenegiant.usb.USBMonitor(this,
                        new com.serenegiant.usb.USBMonitor.OnDeviceConnectListener() {
                            public void onAttach(UsbDevice device) {}
                            public void onDettach(UsbDevice device) {}
                            public void onConnect(UsbDevice device,
                                                  com.serenegiant.usb.USBMonitor.UsbControlBlock ctrlBlock,
                                                  boolean createNewFile) {}
                            public void onDisconnect(UsbDevice device,
                                                     com.serenegiant.usb.USBMonitor.UsbControlBlock ctrlBlock) {}
                            public void onCancel(UsbDevice device) {}
                        });
                com.serenegiant.usb.USBMonitor.UsbControlBlock cb = mon.openDevice(d);
                if (cb == null) {
                    Log.w(TAG, "saki 实验: openDevice 返回 null（权限/占用）");
                    return;
                }
                com.serenegiant.usb.UVCCamera cam = new com.serenegiant.usb.UVCCamera();
                cam.open(cb);
                Log.i(TAG, "saki 实验: camera.open OK（native 已按 fd 解析描述符）");
                final int[] cnt = {0};
                cam.setFrameCallback(frame -> {
                    int n = (frame != null) ? frame.limit() : -1;
                    cnt[0]++;
                    if (cnt[0] <= 3 || cnt[0] % 60 == 0) {
                        Log.i(TAG, "saki 实验: ★★ 收到帧 #" + cnt[0] + " " + n + " 字节");
                    }
                }, com.serenegiant.usb.UVCCamera.PIXEL_FORMAT_RAW);
                android.graphics.SurfaceTexture st = new android.graphics.SurfaceTexture(0);
                cam.setPreviewTexture(st);      // saki 需绑定 Surface 才启动等时传输（无头占位）
                cam.startPreview();             // 此版本 startPreview 不带参数
                Log.i(TAG, "saki 实验: startPreview 已发出，等 8 秒看有没有帧…");
                Thread.sleep(8000);
                Log.i(TAG, "saki 实验: ★ 结论 —— 共收到 " + cnt[0] + " 帧"
                        + (cnt[0] > 0 ? "（原生栈能读 ⇒ 我们自己实现的问题）"
                                      : "（原生栈也读不到 ⇒ 相机/内核层问题）"));
            } catch (Throwable t) {
                Log.e(TAG, "saki 实验异常: " + t);
            } finally {
                try { if (mon != null) mon.unregister(); } catch (Throwable ignored) {}
            }
        }, "saki-test").start();
    }

    /**
     * ★★「应用侧绕过」探针（后台线程）。
     *
     * <p>系统没暴露接口时，用**我们自己从原始描述符解析出的接口号**去反射 claim 并试读一次。
     * 这是整个「绕过」功能的命门：只要 claim 能成功，后续就能在这条通道上做 UVC 协商 + 取流，
     * 完全不经过 Android 的接口枚举。
     */
    private void tryBypass(UsbDevice d) {
        new Thread(() -> {
            UsbDeviceConnection c = null;
            try {
                c = usbManager.openDevice(d);
                if (c == null) {
                    Log.w(TAG, "绕过探针: openDevice 失败");
                    return;
                }
                byte[] cfg = UvcDescriptors.readConfig(c);
                java.util.List<UvcDescriptors.ItfInfo> list =
                        UvcDescriptors.findStreamingInterfaces(cfg);
                // ★ 把端点类型记下来：供 classify() 给系统枚举失败的设备正确归类
                RawUvc.rememberRawEndpointType(d, list);
                // 归类变了（NONE → BULK/ISO）要让列表重建，这类设备就不再显示"不可用"了
                runOnUiThread(MainActivity.this::refreshDeviceList);
                if (list.isEmpty()) {
                    Log.w(TAG, "绕过探针: 原始描述符里没有「带流端点的视频流接口」");
                    return;
                }
                Log.i(TAG, "绕过探针: " + d.getDeviceName() + " → " + list.get(0));
                // BULK → Java bulkTransfer；ISO → libusb（Android API 不支持 ISO）
                String r;
                if (list.get(0).epType == 2) {
                    r = RawUvc.claimAndRead(c, list.get(0));
                } else {
                    // ★ 注意：不要在这里再开一个 UVCCamera 做实验 —— 它会占住设备，
                    //   导致真正的 IsoUvcSession 打开失败（实测 nativeConnect 返回 -99）。
                    //   需要复验原生栈时用 testSakiNativeOnDevice()，且必须先停掉转发。
                    r = RawUvc.streamIsoLibusb(c, list);
                }
                Log.i(TAG, "★★ 绕过探针结果: " + r);
            } catch (Throwable t) {
                Log.e(TAG, "绕过探针异常: " + t);
            } finally {
                if (c != null) {
                    try { c.close(); } catch (Throwable ignored) {}
                }
            }
        }, "raw-uvc-probe").start();
    }

    /** 已授权设备：开一次临时连接，读原始描述符确认它是不是相机 */
    private boolean rawHasVideo(UsbDevice d) {
        UsbDeviceConnection c = null;
        try {
            c = usbManager.openDevice(d);
            return UvcDescriptors.hasVideoInterface(c);
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 系统未暴露接口的相机（Pico 4 / Android 10 上描述符解析失败的那类）：
     * 只显示名称 + 原因，**不提供转发按钮**（因为没有可用接口，转发必然失败）。
     */
    private android.view.View buildUnusableRow(UsbDevice dev) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card_inner);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(lp);

        android.widget.TextView nm = new android.widget.TextView(this);
        nm.setText(UvcStreamer.deviceDisplayName(dev, null));
        nm.setTextColor(getColorRes(R.color.text_primary));
        nm.setTextSize(14);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(nm);

        android.widget.TextView why = new android.widget.TextView(this);
        why.setText(getString(R.string.unusable_reason));
        why.setTextColor(isDarkMode() ? 0xFFEF5350 : 0xFFC62828);
        why.setTextSize(12);
        row.addView(why);

        // ★ 未授权时给一个「授权」按钮 —— Pico/Quest 这类 VR 系统**不会**为
        //   requestPermission() 弹权限框，必须由 App 主动发起（用户戴着头显点一下）。
        if (!usbManager.hasPermission(dev)) {
            android.widget.Button b = new android.widget.Button(this);
            b.setText(R.string.btn_grant);
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setBackgroundResource(R.drawable.bg_button_outline);
            b.setTextColor(getColorRes(R.color.text_primary));
            b.setPadding(dp(14), dp(6), dp(14), dp(6));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(8);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> {
                Log.i(TAG, "手动请求 USB 权限: " + dev.getDeviceName());
                pendingAuth.clear();
                pendingAuth.add(dev);
                requestNext();
            });
            row.addView(b);
        }
        return row;
    }

    private void ensurePermissionsThenStart() {
        // ★ USB_CAMERA = Quest (Horizon OS) 外接 UVC 的唯一门禁（dangerous 级，需运行时请求）。
        // 非 Quest 平台**没有声明**这个权限，必须先探测它是否存在再决定是否请求。
        // ⚠️ 注意：不能用 checkSelfPermission 的 try/catch 来判断 ——
        //   它对**不存在的权限名不抛异常**，只返回 DENIED，
        //   于是 Pico/手机上会永远显示"待授权"、每次启动白跑一轮 requestPermissions。
        //   正确判据是 getPermissionInfo()：平台没声明该权限时它会抛 NameNotFoundException。
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(Manifest.permission.CAMERA);
        list.add(Manifest.permission.RECORD_AUDIO);
        String usbCam = "horizonos.permission.USB_CAMERA";
        try {
            getPackageManager().getPermissionInfo(usbCam, 0);   // 平台声明了才加
            list.add(usbCam);
        } catch (Exception notDeclaredOnThisPlatform) {
            // 非 Quest → 静默跳过，不请求、不提示
        }
        String[] needed = list.toArray(new String[0]);

        boolean all = true;
        for (String p : needed) {
            boolean granted;
            try {
                granted = ContextCompat.checkSelfPermission(this, p)
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) {
                // 兜底：系统完全不认识该权限名 → 视为已满足
                granted = true;
            }
            if (!granted) {
                Log.i(TAG, "待授权: " + p);
                all = false;
            }
        }
        if (!all) {
            ActivityCompat.requestPermissions(this, needed, REQ_PERMISSIONS);
            return;
        }
        ensureUsbPermission();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            ensureUsbPermission();
        }
    }

    private void ensureUsbPermission() {
        List<UsbDevice> cams = findCameras();
        if (cams.isEmpty()) {
            tvStatus.setText(R.string.msg_no_camera);
            return;
        }
        pendingAuth.clear();
        for (UsbDevice d : cams) {
            if (!usbManager.hasPermission(d)) pendingAuth.add(d);
        }
        if (pendingAuth.isEmpty()) {
            startForwarding();
            return;
        }
        // ★ Quest (Horizon OS)：requestPermission 会立即回调拒绝（1ms，非用户操作），
        // 而 dumpsys 实际已授予权限。故请求后给一个短超时兜底 —— 无论回调如何，
        // 超时即继续启动转发，由 Service 侧直接尝试 openDevice 实测。
        tvStatus.setText(getString(R.string.msg_cameras_found,
                cams.size(), pendingAuth.size()));
        requestNext();
    }

    /** 依次请求队列中下一个设备的 USB 权限；队列空则启动转发 */
    private void requestNext() {
        UsbDevice dev = pendingAuth.poll();
        if (dev == null) {
            startForwarding();
            return;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        // ★ 显式限定包名：Android 12+ 对隐式 Intent 有拦截，Quest (Horizon OS)
        // 上隐式 PendingIntent 会导致系统权限对话框不弹出、回调永不触发
        intent.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        usbManager.requestPermission(dev, pi);
        // ★ 超时兜底：Quest 上系统对话框可能不显示（回调永不返回），
        // 2.5 秒后无论是否收到回调都继续，避免界面永久卡在"待授权"。
        // 若系统其实已授予权限(hasPermission 为 true)，后续流程可正常开流。
        final UsbDevice pending = dev;
        tvStatus.postDelayed(() -> {
            if (pendingAuth.contains(pending)) {
                pendingAuth.remove(pending);
                Log.w(TAG, "授权回调超时，跳过该设备继续: " + pending.getDeviceName());
                requestNext();
            }
        }, 2500);
    }

    private void startForwarding() {
        Intent svc = new Intent(this, UsbForwardService.class);
        svc.putExtra(UsbForwardService.EXTRA_IP, etIp.getText().toString().trim());
        svc.putExtra(UsbForwardService.EXTRA_PORT,
                parseIntSafe(etPort.getText().toString(), UvcProto.NET_PORT_DEFAULT));
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        btnToggle.setText(R.string.btn_stop);
        serviceRunning = true;
        tvStatus.setText(R.string.status_starting);
    }

    private void stopServiceSafe() {
        try {
            stopService(new Intent(this, UsbForwardService.class));
        } catch (Exception ignored) {}
        serviceRunning = false;
        activeDevices.clear();
        btnToggle.setText(R.string.btn_start);
        refreshDeviceList();
    }

    // ================= 设备列表 + 单设备转发 =================

    /** 重建"已连接摄像头"列表（每设备一行：信息 + 单独转发/停止按钮） */
    private void refreshDeviceList() {
        if (deviceList == null) return;
        deviceList.removeAllViews();
        List<UsbDevice> cams = findCameras();
        // ★ 对照实验（见 DEBUG_BYPASS_ALL_CAMERAS）：对已暴露相机也跑一次 libusb 绕过探针
        if (DEBUG_BYPASS_ALL_CAMERAS) {
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                if (d.getInterfaceCount() > 0
                        && UvcStreamer.classify(d) != UvcStreamer.UvcKind.NONE
                        && usbManager.hasPermission(d)
                        && probedRaw.add(d.getDeviceName())) {
                    Log.i(TAG, "对照实验：对系统已识别的相机也跑绕过探针 " + d.getDeviceName());
                    tryBypass(d);
                }
            }
        }
        if (cams.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.hint_no_device);
            tv.setTextColor(getColorRes(R.color.text_secondary));
            tv.setTextSize(12);
            tv.setPadding(dp(4), dp(6), dp(4), dp(6));
            deviceList.addView(tv);
            return;
        }
        for (UsbDevice d : cams) {
            deviceList.addView(UvcStreamer.classify(d) == UvcStreamer.UvcKind.NONE
                    ? buildUnusableRow(d) : buildDeviceRow(d));
        }
    }

    /** 跟随系统深浅色模式(背景色随之变化，文字颜色需相应调整) */
    private boolean isDarkMode() {
        int mask = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private android.view.View buildDeviceRow(UsbDevice dev) {
        final String name = dev.getDeviceName();
        UvcStreamer.UvcKind kind = UvcStreamer.classify(dev);
        final boolean authed = usbManager.hasPermission(dev);
        // 已授权时用**一次**临时连接同时读：UVC 描述符名(优先) + 真实能力表(供规格显示/预测)
        UsbDeviceConnection tmpConn = null;
        List<UvcDescriptors.Format> table = null;
        if (authed) {
            try {
                tmpConn = usbManager.openDevice(dev);
                if (tmpConn != null && kind == UvcStreamer.UvcKind.ISO) {
                    table = UvcDescriptors.readQuiet(tmpConn);
                }
            } catch (Exception ignored) {}
        }
        final String display = UvcStreamer.deviceDisplayName(dev, tmpConn);
        if (tmpConn != null) { try { tmpConn.close(); } catch (Exception ignored) {} }
        final boolean active = activeDevices.contains(name);

        String kindLabel = getString(kind == UvcStreamer.UvcKind.BULK
                ? R.string.kind_bulk : R.string.kind_iso);
        String state = active ? getString(R.string.state_forwarding)
                : (authed ? getString(R.string.state_idle_device)
                          : getString(R.string.state_unauthorized));
        // 状态色：深色模式下用亮一档的色，保证可读
        boolean dark = isDarkMode();
        int stateColor = active ? (dark ? 0xFF4CAF50 : 0xFF2E7D32)
                : (authed ? getColorRes(R.color.text_secondary)
                          : (dark ? 0xFFEF5350 : 0xFFC62828));

        // 卡片式设备行：圆角 + 内边距 + 卡片间距
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card_inner);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpRow.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(lpRow);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpInfo = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoCol.setLayoutParams(lpInfo);

        TextView tvName = new TextView(this);
        tvName.setText(display);
        tvName.setTextSize(13);
        tvName.setTextColor(getColorRes(R.color.text_primary));
        tvName.setSingleLine(true);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        infoCol.addView(tvName);

        // 规格行：只有 ISO 显示（BULK 是自家固件，规格固定）
        String specLine = (kind == UvcStreamer.UvcKind.ISO)
                ? specTextFor(dev, active, table) : null;

        TextView tvSub = new TextView(this);
        tvSub.setText(specLine == null
                ? (kindLabel + " · " + state)
                : (kindLabel + " · " + state + " · " + specLine));
        tvSub.setTextSize(11);
        tvSub.setTextColor(stateColor);
        LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSub.topMargin = dp(2);
        tvSub.setLayoutParams(lpSub);
        infoCol.addView(tvSub);
        row.addView(infoCol);

        Button btn = new Button(this);
        btn.setText(active ? R.string.btn_forward_stop : R.string.btn_forward);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        btn.setBackgroundResource(R.drawable.bg_button_primary);
        btn.setTextColor(0xFFFFFFFF);
        btn.setPadding(0, 0, 0, 0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(dp(66), dp(38));
        lpBtn.setMargins(dp(8), 0, 0, 0);
        btn.setLayoutParams(lpBtn);
        btn.setOnClickListener(v -> {
            if (active) {
                stopOne(dev);
            } else {
                startOne(dev);
            }
        });

        // 「规格」按钮：只有 ISO 免驱相机需要（BULK 是自家固件，规格固定）
        if (kind == UvcStreamer.UvcKind.ISO) {
            Button btnSpec = new Button(this);
            btnSpec.setText(R.string.btn_spec);
            btnSpec.setTextSize(12);
            btnSpec.setAllCaps(false);
            btnSpec.setBackgroundResource(R.drawable.bg_button_outline);
            btnSpec.setTextColor(getColorRes(R.color.text_primary));
            btnSpec.setPadding(0, 0, 0, 0);
            btnSpec.setMinWidth(0);
            btnSpec.setMinimumWidth(0);
            LinearLayout.LayoutParams lpSpec = new LinearLayout.LayoutParams(dp(56), dp(38));
            lpSpec.setMargins(dp(8), 0, 0, 0);
            btnSpec.setLayoutParams(lpSpec);
            btnSpec.setEnabled(authed);
            btnSpec.setOnClickListener(v -> openSpecDialog(dev));
            row.addView(btnSpec);
        }
        row.addView(btn);
        return row;
    }

    // ================= 视频规格（手动选择：分辨率 × 帧率） =================

    /**
     * 规格列表里**隐藏低于此帧率**的档位。
     * 原因：低于 30fps 对转发没有实用价值（实测在 GC0308 上选 10fps 档，
     * 实际仍按 30fps 出流），留着只会误导。
     */
    private static final int MIN_SPEC_FPS = 30;

    /**
     * 某相机「当前生效 / 将会生效」的规格文本，带来源标注：
     * <ul>
     *   <li>用户选过 →「手动（MJPEG 640x400@210）」</li>
     *   <li>自动     →「自动（MJPEG 640x480@120）」（按描述符能力表 + 同一套策略预测）</li>
     * </ul>
     * 正在转发的路优先用 Service 回报的**实际值** —— 它可能与预测不同
     * （例如手动档设置失败后自动回落）。
     */
    private String specTextFor(UsbDevice dev, boolean active,
                               List<UvcDescriptors.Format> table) {
        UvcSpecPrefs.Spec manual = UvcSpecPrefs.parse(
                UvcSpecPrefs.get(this, UvcStreamer.deviceKey(dev)));
        String prefix = getString(manual != null
                ? R.string.spec_prefix_manual : R.string.spec_prefix_auto);

        String detail = active ? deviceSpec.get(dev.getDeviceName()) : null;
        if (detail == null) {
            if (manual != null) {
                detail = manual.describe();
            } else if (table != null && !table.isEmpty()) {
                detail = IsoUvcSession.predictAutoSpec(table);
            }
        }
        return detail == null ? prefix : getString(R.string.spec_label, prefix, detail);
    }

    /** 对话框里的一条可选规格 */
    private static final class SpecOption {
        final int fmt, w, h, fps;

        SpecOption(int fmt, int w, int h, int fps) {
            this.fmt = fmt;
            this.w = w;
            this.h = h;
            this.fps = fps;
        }

        boolean mjpeg() { return fmt == UvcDescriptors.FRAME_FORMAT_MJPEG; }

        String label() {
            return (mjpeg() ? "MJPEG" : "YUYV") + "  " + w + "x" + h
                    + (fps > 0 ? "  @" + fps + "fps" : "");
        }
    }

    /**
     * 打开「规格」选择框：列出**从 UVC 描述符解析出的真实能力**
     * （格式 × 分辨率 × 帧率），选完即存（按设备 key），正在转发的会重启以立即生效。
     */
    private void openSpecDialog(UsbDevice dev) {
        if (!usbManager.hasPermission(dev)) {
            Toast.makeText(this, R.string.spec_need_auth, Toast.LENGTH_SHORT).show();
            return;
        }
        // 读真实能力表：临时连接，读完即关
        List<UvcDescriptors.Format> table = null;
        UsbDeviceConnection conn = null;
        try {
            conn = usbManager.openDevice(dev);
            if (conn != null) table = UvcDescriptors.read(conn, dev);
        } catch (Exception e) {
            Log.w(TAG, "读规格表异常: " + e);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignored) {}
            }
        }

        final List<String> labels = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        labels.add(getString(R.string.spec_auto));
        values.add(UvcSpecPrefs.AUTO);

        if (table != null && !table.isEmpty()) {
            List<SpecOption> opts = new ArrayList<>();
            for (UvcDescriptors.Format f : table) {
                for (UvcDescriptors.Frame fr : f.frames) {
                    if (fr.width <= 0 || fr.height <= 0) continue;
                    float[] fps = fr.fps();
                    if (fps.length == 0) {
                        // 帧率未知：保留（不猜）
                        opts.add(new SpecOption(f.frameFormat, fr.width, fr.height, 0));
                    } else {
                        for (float v : fps) {
                            int r = Math.round(v);
                            if (r < MIN_SPEC_FPS) continue;   // 隐藏低帧率档位
                            opts.add(new SpecOption(f.frameFormat, fr.width, fr.height, r));
                        }
                    }
                }
            }
            // MJPEG 优先 → 面积大优先 → 帧率高优先
            opts.sort((a, b) -> {
                if (a.mjpeg() != b.mjpeg()) return a.mjpeg() ? -1 : 1;
                int aa = a.w * a.h, bb = b.w * b.h;
                if (aa != bb) return bb - aa;
                return b.fps - a.fps;
            });
            for (SpecOption o : opts) {
                labels.add(o.label());
                values.add(UvcSpecPrefs.encode(o.fmt, o.w, o.h, o.fps));
            }
        }

        final String key = UvcStreamer.deviceKey(dev);
        int checked = values.indexOf(UvcSpecPrefs.get(this, key));
        if (checked < 0) checked = 0;
        if (values.size() <= 1) {
            Toast.makeText(this, R.string.spec_unavailable, Toast.LENGTH_LONG).show();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.spec_dialog_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked,
                        (dlg, which) -> {
                            String v = values.get(which);
                            UvcSpecPrefs.set(MainActivity.this, key, v);
                            UvcSpecPrefs.Spec sp = UvcSpecPrefs.parse(v);
                            String shown = (sp == null)
                                    ? getString(R.string.spec_auto) : sp.describe();
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.spec_saved, shown),
                                    Toast.LENGTH_SHORT).show();
                            dlg.dismiss();
                            // 正在转发 → 重启该路让新规格立即生效
                            if (activeDevices.contains(dev.getDeviceName())) {
                                stopOne(dev);
                                new android.os.Handler(getMainLooper()).postDelayed(
                                        () -> startOne(dev), 700);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 取颜色资源(自动适配深浅色) */
    private int getColorRes(int resId) {
        if (Build.VERSION.SDK_INT >= 23) return getColor(resId);
        return getResources().getColor(resId);
    }

    /** 单独转发某设备：未授权则先弹该设备的授权框 */
    private void startOne(UsbDevice dev) {
        if (TextUtils.isEmpty(etIp.getText())) {
            tvStatus.setText(R.string.msg_need_ip);
            return;
        }
        savePrefs();
        if (!usbManager.hasPermission(dev)) {
            pendingSingleName = dev.getDeviceName();
            requestSingleAuth(dev);
            return;
        }
        startDeviceAction(dev, UvcProto.DEVICE_ACTION_START);
    }

    private void stopOne(UsbDevice dev) {
        startDeviceAction(dev, UvcProto.DEVICE_ACTION_STOP);
    }

    private void requestSingleAuth(UsbDevice dev) {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        usbManager.requestPermission(dev, pi);
    }

    /** 向 Service 发单设备启停命令（service 未跑时 start 会自动拉起） */
    private void startDeviceAction(UsbDevice dev, String action) {
        Intent svc = new Intent(this, UsbForwardService.class);
        svc.putExtra(UsbForwardService.EXTRA_IP, etIp.getText().toString().trim());
        svc.putExtra(UsbForwardService.EXTRA_PORT,
                parseIntSafe(etPort.getText().toString(), UvcProto.NET_PORT_DEFAULT));
        svc.putExtra(UvcProto.EXTRA_DEVICE_NAME, dev.getDeviceName());
        svc.putExtra(UvcProto.EXTRA_DEVICE_ACTION, action);
        try {
            if (UvcProto.DEVICE_ACTION_START.equals(action) && Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "device action failed", e);
        }
        if (UvcProto.DEVICE_ACTION_START.equals(action)) {
            tvStatus.setText(getString(R.string.msg_setting_up, dev.getDeviceName()));
        }
        serviceRunning = isServiceRunning() || UvcProto.DEVICE_ACTION_START.equals(action);
        btnToggle.setText(serviceRunning ? R.string.btn_stop : R.string.btn_start);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 局域网自动发现 PC 端 UVCReceiver（receiver.py 需先运行） */
    private void discoverPc() {
        btnScan.setEnabled(false);
        tvStatus.setText(R.string.msg_scanning);
        new Thread(() -> {
            String found = null;
            DatagramSocket sock = null;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiManager.MulticastLock lock = wm.createMulticastLock("uvc_discover");
                lock.setReferenceCounted(false);
                lock.acquire();
                try {
                    sock = new DatagramSocket(UvcProto.NET_BEACON_PORT);
                    sock.setBroadcast(true);
                    sock.setSoTimeout(2500);
                    byte[] msg = UvcProto.NET_DISCOVER_MSG.getBytes("UTF-8");
                    // 双路广播：有限广播 + 子网定向广播（部分路由器/AP 隔离下有限广播回程不稳）
                    sock.send(new DatagramPacket(msg, msg.length,
                            InetAddress.getByName("255.255.255.255"), UvcProto.NET_BEACON_PORT));
                    String subnetBc = subnetBroadcast();
                    if (subnetBc != null) {
                        try {
                            sock.send(new DatagramPacket(msg, msg.length,
                                    InetAddress.getByName(subnetBc), UvcProto.NET_BEACON_PORT));
                        } catch (Exception ignored) {}
                    }
                    long deadline = System.currentTimeMillis() + 2500;
                    byte[] buf = new byte[256];
                    while (System.currentTimeMillis() < deadline) {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        try { sock.receive(pkt); } catch (java.net.SocketTimeoutException e) { continue; }
                        String reply = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                        if (reply.startsWith(UvcProto.NET_REPLY_PREFIX)) {
                            String ip = reply.substring(UvcProto.NET_REPLY_PREFIX.length()).trim();
                            if (!TextUtils.isEmpty(ip)) { found = ip; break; }
                        }
                    }
                } finally {
                    try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                    try { lock.release(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "discover failed", e);
            }
            final String f = found;
            runOnUiThread(() -> {
                btnScan.setEnabled(true);
                if (f != null) {
                    etIp.setText(f);
                    tvStatus.setText(getString(R.string.msg_pc_found, f));
                } else {
                    tvStatus.setText(R.string.msg_pc_not_found);
                }
            });
        }, "uvc-discover").start();
    }

    /** 计算当前 WiFi 的子网定向广播地址（如 192.168.11.255）；失败返回 null */
    private String subnetBroadcast() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo dhcp = wm.getDhcpInfo();
            if (dhcp == null || dhcp.ipAddress == 0) return null;
            int bc = (dhcp.ipAddress & dhcp.netmask) | (~dhcp.netmask);
            return String.format(java.util.Locale.US, "%d.%d.%d.%d",
                    bc & 0xFF, (bc >> 8) & 0xFF, (bc >> 16) & 0xFF, (bc >> 24) & 0xFF);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildStatsText() {
        return "USB 读流正常（帧统计见通知栏）";
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
