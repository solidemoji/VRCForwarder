# Quest (Horizon OS) USB 摄像头访问 — 官方机制调查结论

> 调查日期：2026-09-17（Quest 3S 实测 + 官方/开源项目交叉验证）
> 状态：**根因已确认，待明天实测验证**

## 一句话结论

**Quest 上访问外接 UVC 摄像头的唯一门禁是 `horizonos.permission.USB_CAMERA`**。
缺少它 → `UsbManager.openDevice()` 直接抛 `SecurityException`。
`android.permission.CAMERA` **不是**这个门禁（它管头显自身的摄像头）。

## 证据链

### 1. 我们自己抓到的系统拒绝理由（最直接）

```
I UsbUserPermissionManager: USB Camera permission required for USB video class devices
E UsbManager: SecurityException: User has not given 10156/com.example.uvcforwarder
              permission to access device /dev/bus/usb/001/003
  Caused by: UsbUserPermissionManager.checkPermission(...:678)
             UsbHostManager.openDevice(...:595)
```

→ 系统明说：**"USB video class devices 需要 USB Camera permission"**。
我当时误读为 `android.permission.CAMERA`，实际它指的是 **`USB_CAMERA`**。

### 2. 关键对照事实（用户提供，推翻了我之前的 6 个错误根因）

- **免驱 ISO UVC 设备**：列表里**直接变绿**（权限弹窗被授予）
- **XIAO bulk UVC 设备**：列表里**显示未授权**

但注意：**"变绿" ≠ 能打开**。变绿只代表 `hasPermission()` 为 true（弹窗授权成功）；
**真正 `openDevice()` 时两者都会撞上 `USB_CAMERA` 门禁**。
（用户尚未在 ISO 设备上完整走通"打开→出流"，需明天验证。）

### 3. 开源项目实测权限矩阵（权威交叉验证）

来源：`1hira-c/horizonos-usb-cam-streamer`（MIT，Quest 上做 UVC 转发的专门项目）

| 访问路径 | 结果 |
|---|---|
| Camera2 API（标准相机框架） | ⛔ EXTERNAL 摄像头不会被枚举 / 无法 open |
| Camera1 API（旧 `android.hardware.Camera`） | ⛔ UVC 不被枚举（同一 Camera HAL） |
| V4L2 节点（`/dev/video*` 直接 open） | ⛔ EACCES / 节点不存在（`uvcvideo` 未绑定） |
| 纯 Java USB Host API（无 native，仅 bulk/control） | ⚠️ **无法取流**（Java 无 iso 传输 API = Android 硬限制） |
| **原生 USB（USB Host API + 自研 usbdevfs ioctl/JNI）+ `USB_CAMERA` 权限** | ✅ **唯一可行**：列挙→open→fd→claim→control→iso URB reap 全通 |
| 原生 USB 但**无 `USB_CAMERA` 权限** | ⛔ **open 被弹掉** |
| 原生 USB + 无 `CAMERA` 但有 `USB_CAMERA` | ✅ **仍可行**（证明 CAMERA 非必需） |

原文要点：
> UVC アクセスのゲートである `horizonos.permission.USB_CAMERA` は
> **Horizon OS アプリストアでは許可されない**ため、この方式で UVC を扱えるのは
> **開発者モード／サイドロード限定の特権**として成立しているものです。

→ **`USB_CAMERA` 是开发者模式/侧载专属特权，商店上架不被允许。**

### 4. Meta 官方参考实现

`facebookresearch/ocean` 的 **External Camera** 示例（Meta 官方研究项目）：
- 同样允许连接/访问外接 USB 摄像头
- 由 `OceanUSBManager`（Java 层）+ native 层处理
- 已测试机型：Ailipu HD USB Camera、ELP 3D USB Camera、Groov-e、**Sonix USB 2.0 Camera**
  （注意 **Sonix = VID 0x0C45**，正是我们那台"能变绿"的 ISO 设备的厂商！）
- 提供多摄像头选择（USB Hub 场景）

## 对我们的影响

### 现状差距

| 项 | 我们 | 要求 |
|---|---|---|
| manifest 声明 `horizonos.permission.USB_CAMERA` | ❌ **未声明** | ✅ 必须 |
| ISO 传输能力 | ❌ 纯 Java（`AndroidUSBCamera`/saki 走 `UsbDeviceConnection`） | ✅ 必须 native `usbdevfs ioctl` |
| bulk 传输（XIAO） | ✅ 已有（`UvcStreamer` 纯 Java bulkTransfer） | bulk 理论可行，但仍需 `USB_CAMERA` 才能 open |

### 关键待验证问题（明天开机第一件事）

1. **`horizonos.permission.USB_CAMERA` 在本机是否存在？保护级别是什么？**
   ```powershell
   adb -s <quest> shell dumpsys package permissions | Select-String 'USB_CAMERA' -Context 0,5
   adb -s <quest> shell pm list permissions | Select-String 'USB_CAMERA'
   ```
   - 若为 `normal` 级 → **加一行 manifest 即可**，App 立刻能 open（bulk 路直接通！）
   - 若为 `signature|privileged` 级 → 侧载也拿不到，需另想办法（或用 Quest 开发者模式特权）

2. **加了权限后，XIAO 的 bulk 路能否 open？**
   我们的 `UvcStreamer` 用的是 **bulkTransfer（Java 支持）**，
   所以**不需要那套 native usbdevfs/iso 实现**——只要 `USB_CAMERA` 到手，bulk 路应该能通。

3. **ISO 免驱设备呢？** 需要 iso 传输，Java 不支持 → 必须上 native（或放弃 Quest 上的 ISO 路）。

## 明天的行动清单

```
① 开机，确认 horizonos.permission.USB_CAMERA 的存在与保护级别
② manifest 加：<uses-permission android:name="horizonos.permission.USB_CAMERA" />
③ 卸载重装（清旧权限记录），授予该权限
④ 插 XIAO，点「开始转发」→ 看 openDevice 是否成功
⑤ 若成功 → bulk 路在 Quest 上打通，ISO 路需另评估
```

## 若 USB_CAMERA 拿不到（备选方向）

| 方向 | 说明 |
|---|---|
| Quest 开发者模式特权 | `USB_CAMERA` 被描述为"开发者模式/侧载专属"，可能需在开发者模式下才行 |
| 走 Meta 官方 External Camera 路径 | 参考 `ocean` 的 `OceanUSBManager` 实现 |
| 文档标注 Quest 为实验性支持 | 主推 Pico（同一 APK 在 Pico 4 Ultra 可正常工作） |

## 参考链接

- `1hira-c/horizonos-usb-cam-streamer` — https://github.com/1hira-c/horizonos-usb-cam-streamer
  （Quest UVC 转发参考实现，含权限矩阵、JNI usbdevfs 实现、MJPEG 服务）
- `facebookresearch/ocean` External Camera —
  https://facebookresearch.github.io/ocean/docs/demoapps/questapps/externalcamera/
- Meta 文档站：https://developers.meta.com/horizon/documentation/android-apps/horizonos-apps

## 附带教训（写给下次的自己）

本轮在 Quest USB 问题上先后给出 **7 个错误根因**（面板 manifest → 单口冲突 → 弹窗不渲染 →
烧错固件 → 陈旧设备对象 → 系统禁 UVC → device_filter 抑制弹窗），每次都"有实证"却都错。

**真正的答案在官方/开源文档里，不在 `dumpsys` 快照里。**
**教训：排障卡住 2 轮以上，立刻去查官方文档 + 找同场景开源实现（GitHub），
而不是继续在本机 `dumpsys`/`logcat` 里穷举状态。**
—— 用搜索引擎搜「<平台> <需求> github」往往一击命中，比自己猜快一个数量级。
