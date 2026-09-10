package com.example.uvcforwarder;

import android.Manifest;
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
                    if (act) activeDevices.add(name); else activeDevices.remove(name);
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
            if (UvcStreamer.classify(d) != UvcStreamer.UvcKind.NONE) out.add(d);
        }
        return out;
    }

    private void ensurePermissionsThenStart() {
        String[] needed = new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean all = true;
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) all = false;
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
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        usbManager.requestPermission(dev, pi);
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
            deviceList.addView(buildDeviceRow(d));
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
        // 已授权时可用临时连接读 UVC 描述符名(优先)，否则退 USB 产品名
        UsbDeviceConnection tmpConn = null;
        if (usbManager.hasPermission(dev)) {
            try { tmpConn = usbManager.openDevice(dev); } catch (Exception ignored) {}
        }
        final String display = UvcStreamer.deviceDisplayName(dev, tmpConn);
        if (tmpConn != null) { try { tmpConn.close(); } catch (Exception ignored) {} }
        UvcStreamer.UvcKind kind = UvcStreamer.classify(dev);
        final boolean active = activeDevices.contains(name);
        final boolean authed = usbManager.hasPermission(dev);

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

        TextView tvSub = new TextView(this);
        tvSub.setText(kindLabel + " · " + state);
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
        row.addView(btn);
        return row;
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
