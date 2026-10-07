# VRCForwarder · 项目介绍

把插在 **VR 一体机 / 安卓手机** 上的 USB 摄像头画面，通过局域网**无线转发到 PC**，
供 VRChat 面捕 / 眼追软件使用。

**项目地址：<https://github.com/solidemoji/VRCForwarder>** ｜
**下载：<https://github.com/solidemoji/VRCForwarder/releases>**

---

## 这是什么

一套**双端**开源工具：

| 组件 | 运行位置 | 作用 |
|---|---|---|
| **VRCForwarder** | VR 一体机 / 安卓手机 | 读取插在本机 USB 口上的 UVC 摄像头，编码后经 WiFi 发出 |
| **VRCReceiver** | Windows PC | 接收多路视频流，显示画面，并对外提供桌面软件可直接拉取的流地址 |

**典型用途**：VR 一体机内的眼追 / 面捕摄像头（XIAO ESP32-S3 模组、免驱 UVC 摄像头等）
本身只能被头显使用、无法直连 PC。本工具把画面**无线桥接**到 PC，
让 **Baballonia、OpenIris、VRCFaceTracking** 等桌面软件直接吃流。

```
┌────────── VR 一体机 / 安卓手机（VRCForwarder）──────────┐
│  UVC 摄像头 ×N ──USB──> USB Host 读取 ──WiFi(UDP)──┐   │
└──────────────────────────────────────────────────────┼──┘
                                                       ↓ UDP 5000
┌──────────────────── PC（VRCReceiver）────────────────┴──┐
│  多路画面面板 + 每路可一键复制拉流地址                     │
│  ├─ MJPEG over HTTP 桥（:8080）→ Baballonia / OpenCV 系  │
│  └─ 可选：输出到 OBS 虚拟摄像头                           │
└──────────────────────────────────────────────────────────┘
```

---

## 功能

**头显 / 手机端**

- USB Host 直读 UVC 摄像头，自动识别 bulk（XIAO 等自研模组）与免驱 ISO 两类协议
- 多路并发（最多 6 路），热插拔自动恢复，每路可单独启停
- 自动识别摄像头名称，同型号可区分左右眼
- **可手动指定视频规格**（格式 × 分辨率 × 帧率），并显示当前实际生效的规格
- 局域网自动搜索 PC 接收端；界面语言跟随系统（中 / 英 / 日 / 韩 / 俄）

**PC 端**

- 多路画面面板，可单独显示 / 隐藏，新流到达弹系统通知
- 内置 MJPEG over HTTP 桥（`http://127.0.0.1:8080/cam/<设备名>`），
  格式与 OpenIris 固件 WiFi 直推一致，兼容现有眼追生态
- 可选输出到 OBS 虚拟摄像头（供只认 UVC 的软件）
- 提供带向导的安装包（可自选安装位置）与绿色版 zip

---

## 机型支持情况

**下列已标 ✅ 的机型都是我们拿实机跑通的；未列出的机型欢迎下载测试并反馈。**

| 机型 | 系统 | 状态 |
|---|---|---|
| **Pico 4 Ultra** | PICO OS 5 / Android 14 | ✅ 已实测通过 |
| **Quest 3 / 3S** | Horizon OS / Android 14 | ✅ 已实测通过（本机验证机型为 Quest 3S） |
| **Pico 4** | **Android 10 / PICO OS 5.13.7** | ✅ **已实测通过**（标准 UVC 相机：OV9281，MJPEG 640×480@120，实测 100–121 fps） |
| **Pico 4 Pro** | PICO OS 4 / Android 10 | ❓ 未测试 |
| **Quest 2** | Horizon OS | ❓ 未测试 |
| 其它安卓手机 / 平板 | Android 9 及以上 | ❓ 未测试 |

**关于 Pico 4（Android 10）**：我们专门购入一台 Pico 4（`A8110 / Phoenix`，**Android 10 / SDK 29，PICO OS 5.13.7**）实测：

- 标准免驱 UVC 相机（OV9281）**枚举完整** —— 视频控制接口 + 视频流接口（多档 alternate setting + ISO 端点）齐全；
- 能力表读取、自动选档、出流全部正常，**MJPEG 640×480@120，实测 100–121 fps**，与 Quest 3S 基准一致。

> 此前"能识别设备但相机列表为空"的反馈，经实机复现确认**只出现在我们自家 XIAO 固件（USB 复合描述符）上**：
> Android 10 的 USB 枚举不会暴露该复合描述符里的 VideoStreaming 接口（同一份固件在 Windows 与 Android 14 上枚举完全正常）。
> 随后做了穷举验证（有/无 CDC × BULK/ISO × alt 布局，**5 个固件版本全部失败**），
> 系统日志显示 AOSP 解析器在该描述符上抛异常并中止解析 ⇒ **App 侧无法解决**。
> 用自家 XIAO / OpenIris 硬件的 Pico 4 用户请改用**标准免驱 UVC 相机**或固件的 **WiFi 推流模式**。

Android 10 对 UVC 类设备另有一道门禁（AOSP `UsbUserSettingsManager`）：`targetSdk >= 28` 需先取得
`CAMERA` 运行时权限。本 App 已把 `targetSdk` 压到 **27** 并同时申请该权限，实测无阻碍。

**关于 Pico 4 Pro**：**未测试**。同为 Android 10 世代，理论应与 Pico 4 表现一致，但我们**不在未实测的情况下作推断**。

> 本 App 的 `minSdk` 为 **28（Android 9.0）**，低于此版本的系统无法安装。

---

## 系统要求

| 项 | 要求 |
|---|---|
| 头显 / 手机系统 | **Android 9 及以上**。实测通过的是 **Android 14**；Android 10–13 未实测 |
| 网络 | 与 PC **处于同一局域网**即可（Wi-Fi 或网线均可） |
| USB | 设备需支持 **USB Host（OTG）**；多路建议用**带外部供电的 USB Hub** |
| PC | Windows 10 / 11 64 位 |

---

## 下载与安装

### 头显 / 手机端

1. 在设备上**开启开发者模式**，并在「设置 → 开发者」打开 **USB 调试**
2. 下载 Release 中的 `VRCForwarder-*.apk`，**侧载**安装：
   - **Pico**：USB 连接后把 APK 传到头显根目录，在头显内文件管理器中点击安装
   - **Quest**：用 [SideQuest](https://sidequestvr.com/) 安装，或直接 adb 侧载
   - 通用：`adb install -r VRCForwarder-*.apk`

> **本 App 不在任何应用商店上架，只能侧载安装。**

**Quest（Horizon OS）用户请注意**

- 首次使用需**额外授予系统权限** `horizonos.permission.USB_CAMERA` ——
  首次点「开始转发」时**务必点「允许」**
- 该权限在 Horizon OS **应用商店审核中不被允许**，因此本 App 在 Quest 上**只能侧载使用**
- **小提示（不是故障）**：插入 USB 视频设备时系统可能弹出「用某应用打开」或**虚拟屏选择窗口**，
  **可以点「不允许」或直接关掉** —— 由 Quest 系统触发，与本 App 无关

### PC 端

- **安装包（推荐）**：`VRCReceiver-Setup-x.y.z.exe`，按向导安装，可自选安装位置
- **绿色版**：`VRCReceiver-win64.zip`，解压后双击 `VRCReceiver.exe`（整个文件夹一起解压）

### 使用

1. 把 UVC 摄像头插到头显 / 手机的 USB 口（多路建议用带供电的 Hub）
2. 打开 App，填入 PC 的局域网 IP（或点「自动搜索」），点「开始转发」，允许 USB 授权
3. PC 端出现画面面板 → 一键复制面板上的流地址 → 粘进桌面软件即可

---

## 仓库结构

```
VRCForwarder/
├── android/     # 头显 / 手机端 App（Java + Gradle）
└── pc/          # PC 接收端（Python + PySide6）
```

---

## 许可

本项目采用 **MIT License** —— 可自由使用、修改、分发，**包括商业用途**。
**唯一要求**：保留原始版权声明与出处标注。

> 依赖的第三方库各自遵循其原有许可，二次分发时请一并遵守
> （pyvirtualcam 为 GPL-2.0、PySide6/Qt 为 LGPL-3.0，
> 安卓端内嵌的 AndroidUSBCamera / UVCCamera / libcommon 为 Apache-2.0）。

---

*本项目为个人 DIY 项目，测试覆盖机型有限，欢迎在 Issues 反馈使用结果。*
*与所引用的第三方项目作者无隶属或背书关系。*
