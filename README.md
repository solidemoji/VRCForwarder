# VRCForwarder · VR 外接摄像头转发

把插在 **VR 一体机 / 安卓手机** 上的 **USB UVC 摄像头**（眼追、面捕模组等）画面，
通过局域网**无线转发到 PC**，供 **Baballonia、OpenIris、VRCFaceTracking** 等
VRChat 面捕 / 眼追软件直接拉流使用。

```
┌────────── VR 一体机 / 安卓手机（VRCForwarder）──────────┐
│  UVC 摄像头 ×N ──USB──> USB Host 读取 ──WiFi(UDP)──┐   │
│  （XIAO bulk / 免驱 ISO 自动识别）                   │   │
└──────────────────────────────────────────────────────┼──┘
                                                       ↓ UDP 5000
┌──────────────────── PC（VRCReceiver）────────────────┴──┐
│  多路画面面板 + 每路可一键复制拉流地址                     │
│  ├─ MJPEG over HTTP 桥（:8080）→ Baballonia / OpenCV 系  │
│  └─ 可选：输出到 OBS 虚拟摄像头 → 只认 UVC 的软件          │
└──────────────────────────────────────────────────────────┘
```

> 想要一份便于分享的简短介绍：[中文](docs/INTRO.md) · [English](docs/INTRO.en.md)

## 功能

**头显 / 手机端（VRCForwarder）**

- USB Host 直读 UVC 摄像头，**自动识别两类传输协议**：bulk（XIAO ESP32-S3 等自研模组）与标准免驱 ISO
- **多路并发（最多 6 路）**，热插拔自动恢复，每路可单独启停
- **自动识别摄像头名称**（UVC 描述符名 → USB 产品名 → VID:PID 三级回退），同型号可区分左右眼
- **可手动指定视频规格**（格式 × 分辨率 × 帧率），并显示**当前实际生效**的规格 ——
  自动模式下也会标出自动选中了哪一档（如「自动（MJPEG 640x480@120）」）
- **局域网自动搜索** PC 接收端，无需手填 IP
- 界面语言**跟随系统**：中 / 英 / 日 / 韩 / 俄（其它语言回退英语）
- 亮橙色卡片式界面，为头显内阅读优化

**PC 端（VRCReceiver）**

- **多路画面面板**，可单独显示 / 隐藏，新流到达弹系统通知
- **内置 MJPEG over HTTP 桥**：`http://127.0.0.1:8080/cam/<设备名>` ——
  格式与 OpenIris 固件 WiFi 直推一致，**兼容现有眼追生态**
- **可选输出到 OBS 虚拟摄像头**（供只认 UVC 的软件）
- 五语言界面；帧率、缩放、端口均可用命令行参数调整
- 提供**带向导的安装包**（可自选安装位置、可选开机自启）与绿色版 zip

## 机型支持情况

**先说结论：下列已标 ✅ 的机型都是我们拿实机跑通的；未列出的机型欢迎下载测试并反馈。**

| 机型 | 系统 | 状态 |
|---|---|---|
| **Pico 4 Ultra** | PICO OS 5 / Android 14 | ✅ **已实测通过** |
| **Quest 3 / 3S** | Horizon OS / Android 14 | ✅ **已实测通过**（本机验证机型为 Quest 3S） |
| **Pico 4** | **Android 10 / PICO OS 5.13.7** | ⚠️ **部分相机可用**（免驱 OV9281 实测 100–121 fps；GC0308 免驱模组在此平台**不可用** —— 原因见下） |
| **Pico 4 Pro** | PICO OS 4 / Android 10 | ❓ 未测试 |
| **Quest 2** | Horizon OS | ❓ 未测试 |
| 其它安卓手机 / 平板 | Android 9 及以上 | ❓ 未测试 |

### 关于 Pico 4（Android 10）—— ✅ 已实测通过

我们专门购入一台 Pico 4（`A8110 / Phoenix`，**Android 10 / SDK 29，PICO OS 5.13.7**）做了完整实测：

- ✅ 标准免驱 UVC 相机（OV9281，`0C45:636D`）**枚举完整** —— 视频控制接口 + 视频流接口（多档 alternate setting + ISO 端点）齐全；
- ✅ 能力表读取、自动选档、出流全部正常，帧率与 Quest 3S 基准一致：**MJPEG 640×480@120，实测 100–121 fps**；
- ✅ 安装无阻碍（`minSdk 28` ≤ Android 10）。

**👉 结论：Pico 4（Android 10）能用标准 UVC 相机，但「能不能用」取决于具体相机的 USB 描述符。**

> ⚠️ **重要：Pico 4 的相机兼容性是"看描述符"的，不能笼统说"支持 UVC 相机"。**
> 我们在同一台 Pico 4 上实测：
>
> | 相机 | 结果 |
> |---|---|
> | 免驱 OV9281（`0C45:636D`） | ✅ 枚举完整（VC + VS 多档 alt + ISO 端点），**MJPEG 640×480@120，100–121 fps** |
> | GC0308 免驱模组（`0581:0B20`，带麦克风的复合相机） | ❌ **不可用** —— Android 10 的 USB 描述符解析器在该相机描述符上抛 `IllegalArgumentException` 并中止解析 ⇒ **系统只给出 0 个接口**，任何 App 都拿不到它 |
>
> 同一颗 GC0308 在 Android 14 设备上（Pico 4 Ultra / Quest）是正常可用的 ⇒ **问题出在 Android 10 的枚举层，不在相机、也不在 App**。
> 因此：**买相机前最好先用 App 试一下**；从 v0.0.6 起，这类"系统未暴露接口"的相机会**出现在列表里并标注原因**，同时我们也在排查规避方案。

> **补充说明**：此前有用户反馈 Pico 4「能识别到设备插入，但相机列表为空」——我们在实机上复现后确认，
> **该现象只出现在我们自家 XIAO 固件（USB 复合描述符：CDC + UVC）上**：Android 10 的 USB 枚举
> 不会暴露这类复合描述符中的 VideoStreaming 接口（**同一份固件在 Windows 与 Android 14 上枚举完全正常**）。
>
> 我们随后做了穷举验证 —— **有/无 CDC × BULK/ISO 传输 × alt 布局，共 5 个固件版本全部失败**；
> 系统侧日志可看到 AOSP 的 `UsbDescriptorParser` 在该描述符上抛
> `NegativeArraySizeException` 并中止解析（先把 CDC 的 `0x24` 类描述符误判为音频类而失步）。
> 因此最终结论：**Android 10 的 USB 枚举层不会暴露该固件的视频流接口，App 侧无法解决。**
>
> **使用自家 XIAO / OpenIris 硬件的 Pico 4 用户**：请改用**标准免驱 UVC 相机**（已实测通过），
> 或使用固件自带的 **WiFi 推流模式**。标准 UVC 相机不受此问题影响。
>
> 另外，Android 10 对 UVC 类设备有一道额外门禁（AOSP `UsbUserSettingsManager`）：
> `targetSdk >= 28` 的应用需先取得 `CAMERA` 运行时权限；本 App 已把 `targetSdk` 压到 **27** 并同时申请该权限，
> 两道都做了，实测无阻碍。

### 关于 Pico 4 Pro（Android 10）

**❓ 未测试**。同为 Android 10 世代，理论上应与 Pico 4 表现一致，但我们**尚未在 Pico 4 Pro 上实测**，
因此如实标注、**不做推断**。如果你手上有该机型，欢迎装上试一下并在 [Issues](../../issues) 反馈。

> 注意：本 App 的 `minSdk` 为 **28（Android 9.0）**，低于此版本的系统无法安装。

## 系统要求

| 项 | 要求 |
|---|---|
| 头显 / 手机系统 | **Android 9 及以上**（`minSdk 28`）。**实测通过的是 Android 14**；Android 10–13 未实测 |
| 网络 | 与 PC **处于同一局域网**即可（Wi-Fi 或网线均可；多路同时传输时建议网线或 5GHz Wi-Fi 以留足带宽） |
| USB | 设备需支持 **USB Host（OTG）**；多路建议使用**带外部供电的 USB Hub** |
| PC | Windows 10 / 11 64 位 |

## 下载与安装

**下载**：<https://github.com/solidemoji/VRCForwarder/releases>

### 1. 安卓端（APK 需侧载）

下载 Release 中的 `VRCForwarder-*.apk`。**本 App 不在任何应用商店上架，只能侧载安装**，
因此需要先在设备上**开启开发者模式**（Pico：PICO 应用 / Quest：Meta Horizon → 设备设置），
再在头显内 **设置 → 开发者** 打开 **USB 调试**。

| 设备 | 推荐方式 |
|---|---|
| **Pico 4 Ultra** | USB 连接后把 APK 传到头显**根目录**，在头显内文件管理器中点击安装 |
| **Quest 3 / 3S** | 用 [SideQuest](https://sidequestvr.com/) 等工具安装，或直接 adb 侧载 |
| **其它安卓设备** | `adb install -r VRCForwarder-*.apk`，或拷到设备上用文件管理器点击安装 |

> 本项目正式包已在 manifest 中显式设置 `android:testOnly="false"`。
> 若你遇到 `INSTALL_FAILED_TEST_ONLY`，说明拿到的是调试构建，加 `-t` 参数安装即可。

**Quest（Horizon OS）用户请注意**

1. Quest 上首次使用需**额外授予系统权限** `horizonos.permission.USB_CAMERA` ——
   首次点「开始转发」时**务必点「允许」**；若没弹窗或误拒，可在
   **设置 → 应用 → VRCForwarder → 权限** 中手动打开
2. 该权限在 Horizon OS **应用商店审核中不被允许**，所以本 App 在 Quest 上
   **只能侧载使用**（这也是它能工作的前提）。Pico 与安卓手机无此限制
3. **小提示（不是故障）**：Quest 上插入 USB 视频设备时，系统可能弹出「用某应用打开」
   或**虚拟屏选择窗口**。**可以点「不允许」**，或**直接关掉不用理会** ——
   该弹窗由 Quest 系统自身触发，与本 App 无关，App 侧也无法屏蔽

### 2. PC 端

| 方式 | 说明 |
|---|---|
| **安装包（推荐）** | 下载 `VRCReceiver-Setup-x.y.z.exe`，按向导安装 —— **可自选安装位置**、可选桌面快捷方式与开机自启，无需安装 Python |
| **绿色版** | 下载 `VRCReceiver-win64.zip`，解压后双击 `VRCReceiver.exe`（exe 依赖同目录的 `_internal`，**必须整个文件夹一起解压**） |
| **源码运行** | `cd pc && pip install pyside6 numpy && python vrc_receiver.py`（可选 `pyvirtualcam`） |

### 3. 使用

1. 把 UVC 摄像头插到头显 / 手机的 USB 口（多路建议用带供电的 Hub）
2. 打开 App，填入 PC 的局域网 IP（或点「自动搜索」），点「开始转发」，允许 USB 授权
3. PC 端出现画面面板 → 一键复制面板上的流地址 → 粘进桌面软件即可

## PC 端命令行参数

```
VRCReceiver.exe                       # 默认即可
  --port 5000                         # 接收端口
  --lang zh|en|ja|ko|ru               # 界面语言（默认跟随系统）
  --mjpeg-port 8080                   # MJPEG 桥端口（0=禁用）
  --mjpeg-fps 60                      # 桥输出帧率
  --mjpeg-max-width 0                 # 桥输出缩放宽度（0=原尺寸）
  --display-fps 60                    # 界面显示帧率（0=不限）
  --vcam off|auto|<n>                 # 输出到 OBS 虚拟摄像头（默认关闭）
  --help                              # 查看全部参数（GUI 下弹窗显示）
```

## 常见问题

**Q: 插上摄像头了，但相机列表里没有相机 / 点转发就失败？**

A: ① 先确认机型是否已实测支持（见上方「机型支持情况」）——**未实测的机型请把结果反馈给我们**；
② 摄像头是否被其它 App 占用；③ 换一根数据线 / 换一个 USB 口；
④ Quest 上是否已授予 `horizonos.permission.USB_CAMERA`；⑤ 多路时是否用了**带供电的 USB Hub**。

**Q: 转发画面卡顿 / 帧率低？**

A: ① 头显与 PC 是否在同一局域网（Wi-Fi 或网线均可）；② 多路时是否用了带供电的 Hub；
③ 接收端 `--mjpeg-fps` / `--display-fps` 默认 60，桌面软件处理不过来可下调；
④ 部分桌面软件自身有限帧行为，与本项目无关。

**Q: 摄像头支持的最高帧率没跑到？**

A: 在 App 里点该相机行的「**规格**」按钮，手动指定想要的格式 × 分辨率 × 帧率
（列表来自相机描述符的**真实**能力表）；自动模式只选「最常见帧率」里面积最大的一档。

**Q: 目标软件收不到画面？**

A: 浏览器先打开 `http://127.0.0.1:8080` 确认桥能出图，再确认填的是面板上复制的完整地址。

**Q: 端口 5000 被占用？**

A: 接收端会明确报错而不是静默失败，请关闭其它接收端实例（含旧版本）。

**Q: 头显里找不到装好的 App？**

A: 侧载的第三方 App 通常不出现在主界面应用列表里，请到「**库 / 未知来源 / 全部应用**」下查找。

**Q: 安装时报 `INSTALL_FAILED_TEST_ONLY`？**

A: 说明拿到的是调试构建。本项目 Release 中的正式包已关闭该标记，可直接安装；自行构建的 debug 包用 `adb install -t`。

## 从源码构建

**安卓端**（需 Android SDK + JDK 17+）：

```bash
cd android
./gradlew assembleRelease        # 产物: app/build/outputs/apk/release/app-release.apk
```

> 仓库不含签名证书：直接构建会使用 debug 签名（可正常安装测试）。
> 正式发布请把 `keystore.properties.example` 复制为 `keystore.properties` 并填入自己的 keystore 信息。

**PC 端打包 exe**：

```bash
cd pc
pip install pyinstaller pillow pyside6 numpy
pyinstaller VRCReceiver.spec --noconfirm --clean
# 产物: dist/VRCReceiver/VRCReceiver.exe
```

## 引用的第三方库

**安卓端**

| 库 | 用途 | 许可 |
|---|---|---|
| [AndroidX AppCompat / Activity / ConstraintLayout](https://developer.android.com/jetpack/androidx) | 兼容性与界面基础 | Apache-2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | UI 主题与控件 | Apache-2.0 |
| [AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera) 2.3.4 | 免驱 UVC（ISO 等时传输）采集 | Apache-2.0 |
| [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera) | UVC 底层实现（内嵌于上者） | Apache-2.0 |
| [saki4510t/libcommon](https://github.com/saki4510t/libcommon) 4.1.1 | UVCCamera 运行期依赖 | Apache-2.0 |

> ⚠️ `com.serenegiant:common` 不可 exclude，否则运行时报 `NoClassDefFoundError`。

**PC 端**

| 库 | 用途 | 许可 |
|---|---|---|
| [PySide6 (Qt for Python)](https://www.qt.io/qt-for-python) | 图形界面 | LGPL-3.0 / 商业双许可 |
| [NumPy](https://numpy.org/) | 数组运算 | BSD-3-Clause |
| [Pillow](https://python-pillow.org/) | 轻量图像编解码（打包版使用） | MIT-CMU |
| [OpenCV (opencv-python)](https://github.com/opencv/opencv-python) | 图像编解码（**仅开发环境**，打包时由 `cv2_shim.py` 以 Pillow 替代） | Apache-2.0 |
| [pyvirtualcam](https://github.com/letmaik/pyvirtualcam) | 可选：输出到 OBS 虚拟摄像头 | GPL-2.0 |

> **关于 PySide6 (Qt)**：以 LGPL-3.0 方式使用（动态链接、未修改 Qt 源码）。
> 二次分发请保留 Qt 的许可声明与 LGPL 文本。

**协议参考**：UDP 流协议与 MJPEG 桥输出格式参考了
[OpenIris](https://github.com/loopier/OpenIris) 固件的 StreamServer 实现，以便兼容现有眼追生态。

## 许可

本项目采用 **MIT License** —— 可自由使用、修改、分发，**包括商业用途**。

**唯一要求**：保留原始版权声明与出处标注。

详见 [LICENSE](LICENSE)。

> 依赖的第三方库各自遵循其原有许可，二次分发时请一并遵守：
> **pyvirtualcam 为 GPL-2.0**、**PySide6/Qt 为 LGPL-3.0**、
> 安卓端内嵌的 AndroidUSBCamera / UVCCamera / libcommon 为 Apache-2.0。

## 反馈

这是一个个人 DIY 项目，**测试覆盖的机型很有限**。欢迎在
[Issues](../../issues) 反馈：

- 你在**未测试机型**上的结果（能用 / 不能用 + 系统版本）
- 摄像头型号与可用的规格（分辨率 / 帧率）
- Bug 与改进建议

## 致谢

- [saki4510t](https://github.com/saki4510t) — UVCCamera / libcommon
- [jiangdongguo](https://github.com/jiangdongguo) — AndroidUSBCamera
- [OpenIris 项目](https://github.com/loopier/OpenIris) — 流协议与生态兼容参考

---

*本项目为个人 DIY 项目，与上述第三方项目作者无隶属或背书关系。*
