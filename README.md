# VRCForwarder · VR 外接摄像头转发

把 **USB UVC 摄像头**（VR 眼追 / 面捕模组、XIAO ESP32-S3 等）插到 **安卓手机 / VR 一体机**上，
通过 WiFi 把多路视频流转发到 **PC**，供 VRChat 面捕、眼追软件（Baballonia、OpenIris、VRCFaceTracking 等）使用。

> 适用场景：VR 头显内的眼追/面捕摄像头需要接到 PC 上的桌面软件，但头显上没有可用的 USB 直连路径。

```
┌──────────── VR 一体机 / 安卓手机 (VRCForwarder) ────────────┐
│  UVC 摄像头 ×N ──USB──> USB Host 读取 ──WiFi(UDP)──┐        │
│   (XIAO bulk / 免驱 ISO 自动识别)                   │        │
└─────────────────────────────────────────────────────┼────────┘
                                                      ↓ UDP 5000
┌──────────────────── PC (VRCReceiver) ───────────────┴────────┐
│  多路画面窗口 + 每路可复制拉流地址                              │
│  ├─ MJPEG over HTTP 桥 (:8080) → Baballonia / OpenCV 系软件   │
│  └─ 可选: 输出到 OBS 虚拟摄像头 → 只认 UVC 的软件              │
└───────────────────────────────────────────────────────────────┘
```

## 功能

**安卓 / VR 一体机端（VRCForwarder）**
- USB Host 读取 UVC 摄像头，**自动识别两类协议**：bulk 传输（XIAO ESP32-S3 等）与标准免驱 ISO
- 多路并发（最多 6 路），热插拔自动恢复，单个摄像头可单独启停
- **自动读出摄像头名称**（UVC 描述符名 → USB 产品名 → VID:PID 三级回退），左右眼可区分
- 界面语言跟随系统：中 / 英 / 日 / 韩 / 俄（其它语言回退英语）
- 局域网自动搜索 PC 接收端

**PC 端（VRCReceiver）**
- 多路画面面板，可单独显示/隐藏，新流到达弹系统通知
- **内置 MJPEG over HTTP 桥**：`http://127.0.0.1:8080/cam/<设备名>` 供桌面软件直接拉流
  （流格式与 OpenIris 固件 WiFi 直推一致，兼容 Baballonia 等）
- 可选输出到 **OBS 虚拟摄像头**（供只认 UVC 的软件）
- 界面语言同上五语言

## 目录结构

```
VRCForwarder/
├── android/                 # 安卓 / VR 一体机端 App (Java + Gradle)
│   └── app/src/main/        # 源码、多语言资源、图标
└── pc/                      # PC 接收端 (Python + PySide6)
    ├── vrc_receiver.py      # 主程序：UI + MJPEG 桥 + 虚拟摄像头输出
    ├── vrc_protocol.py      # UDP 协议 / 流重组 / 局域网设备发现
    ├── i18n.py              # 多语言（五语言 + 英语兜底）
    ├── cv2_shim.py          # 轻量图像层（Pillow 实现，打包省 98MB）
    └── VRCReceiver.spec     # PyInstaller 打包配置（exe 瘦身）
```

## 快速开始

### 1. 安装安卓端（APK 侧载）

下载 [Releases](../../releases) 中的 `VRCForwarder.apk`，**通过侧载方式安装到手机 / VR 头显**。

> 本 App 不在任何应用商店上架，只能侧载安装，因此**需要先在头显上开启开发者模式**。

**通用前提：开启开发者模式**

在头显的配套手机 App 中（Pico：PICO 应用 / Quest：Meta Horizon）进入设备设置，
连续点击版本号或直接开启「开发者模式」，然后在头显内 **设置 → 开发者** 中打开 **USB 调试**。

| 设备 | 推荐侧载方式 |
|---|---|
| **Pico（4 / 4 Ultra / Neo 等）** | 用 USB 连接后开启** USB 调试模式**，将 APK 传输到头显**根目录**，再在头显内的文件管理器中点击安装 |
| **Quest（2 / 3 / Pro 等）** | 使用 [SideQuest](https://sidequestvr.com/) 等第三方工具安装，或同样通过 adb 侧载 |

完整的侧载操作步骤（驱动安装、adb 授权、文件管理器选择等）各机型略有差异，
**此处不再赘述，有需要请自行查阅对应机型的教程**。

> 补充说明：
> - 也可用 adb 安装：`adb install -r VRCForwarder.apk`
> - 本项目正式包已在 manifest 中显式设置 `android:testOnly="false"`，
>   若你遇到 `INSTALL_FAILED_TEST_ONLY`，说明拿到的是调试构建，加 `-t` 参数即可。

### 2. 运行 PC 端

**方式 A（推荐）：安装包**

下载 Releases 中的 `VRCReceiver-Setup-x.y.z.exe`，双击按向导安装 —— **可自选安装位置**，
并可选创建桌面快捷方式、开始菜单项与开机自启（常驻接收时用）。无需安装 Python。

**方式 B：绿色版 zip**

下载 Releases 中的 `VRCReceiver-win64.zip`，解压到任意目录后双击 `VRCReceiver.exe`。
（exe 依赖同目录的 `_internal`，**必须整个文件夹一起解压**）

**方式 C：源码运行**
```bash
cd pc
pip install pyside6 numpy            # 可选: pyvirtualcam（虚拟摄像头）
python vrc_receiver.py
```

### 3. 使用

1. 把 UVC 摄像头插到手机 / 一体机的 USB 口（多路建议用带外部供电的 USB Hub）
2. 打开 App，填入 PC 的局域网 IP（或点「自动搜索」），点「开始转发」，允许 USB 授权
3. PC 端出现画面面板；面板上的地址可一键复制，粘到桌面软件里即可

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

## 引用的第三方库

### 安卓端

| 库 | 版本 | 用途 | 许可 |
|---|---|---|---|
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx) | 见 `libs.versions.toml` | 兼容性支持 | Apache-2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | 同上 | UI 主题与控件 | Apache-2.0 |
| [AndroidX Activity / ConstraintLayout](https://developer.android.com/jetpack/androidx) | 同上 | 界面基础 | Apache-2.0 |
| [**AndroidUSBCamera**](https://github.com/jiangdongguo/AndroidUSBCamera) | 2.3.4 | 免驱 UVC（ISO 等时传输）摄像头采集 | Apache-2.0 |
| [**saki4510t/UVCCamera**](https://github.com/saki4510t/UVCCamera) | 内嵌于上者 | UVC 底层实现（Java + native） | Apache-2.0 |
| [**saki4510t/libcommon**](https://github.com/saki4510t/libcommon) | 4.1.1 | UVCCamera 运行期依赖 | Apache-2.0 |

> ⚠️ `com.serenegiant:common` 不可 exclude，否则运行时报 `NoClassDefFoundError`。

### PC 端

| 库 | 用途 | 许可 |
|---|---|---|
| [PySide6 (Qt for Python)](https://www.qt.io/qt-for-python) | 图形界面 | LGPL-3.0 / 商业双许可 |
| [NumPy](https://numpy.org/) | 数组运算 | BSD-3-Clause |
| [OpenCV (opencv-python)](https://github.com/opencv/opencv-python) | 图像编解码（**开发环境用**；打包时由 `cv2_shim.py` 以 Pillow 替代） | Apache-2.0 |
| [Pillow](https://python-pillow.org/) | 轻量图像编解码（打包版使用） | MIT-CMU |
| [pyvirtualcam](https://github.com/letmaik/pyvirtualcam) | 可选：输出到 OBS 虚拟摄像头 | GPL-2.0 |

> **关于 PySide6 (Qt)**：以 LGPL-3.0 方式使用（动态链接、未修改 Qt 源码）。
> 若你二次分发本项目，请保留 Qt 的许可声明与 LGPL 文本。

### 协议与固件参考

- UDP 流协议、MJPEG 桥输出格式参考了 [OpenIris](https://github.com/loopier/OpenIris) 固件的 StreamServer 实现，
  以便与现有眼追生态（Baballonia / EyeTrackVR 等）兼容。

## 从源码构建

**安卓端**（需 Android SDK + JDK 17+）：
```bash
cd android
./gradlew assembleRelease        # 产物: app/build/outputs/apk/release/app-release.apk
```
> 仓库不含签名证书：本地直接构建会使用 debug 签名（可正常安装测试）。
> 正式发布请复制 `keystore.properties.example` 为 `keystore.properties` 并填入自己的 keystore 信息。

**PC 端打包 exe**：
```bash
cd pc
pip install pyinstaller pillow pyside6 numpy
pyinstaller VRCReceiver.spec --noconfirm --clean
# 产物: dist/VRCReceiver/VRCReceiver.exe
```

## 常见问题

**Q: 转发画面卡顿 / 帧率低？**
A: 依次检查：① 手机/一体机与 PC 是否在同一 5GHz WiFi；② 多路摄像头是否用了带供电的 Hub；
③ 接收端 `--mjpeg-fps` 与 `--display-fps` 默认 60，如桌面软件处理不过来可下调；
④ 若桌面软件本身有限帧行为（部分版本会压到 30fps），与本项目无关。

**Q: 为什么目标软件收不到画面？**
A: 浏览器先打开 `http://127.0.0.1:8080` 确认桥正常出图；再确认填的是面板上复制的完整地址。

**Q: PC 端该用安装包还是绿色版 zip？**
A: 两者功能完全相同。**安装包**（`VRCReceiver-Setup-x.y.z.exe`）适合长期使用 ——
可自选安装目录、自动创建快捷方式、支持开机自启、可在「应用和功能」里正常卸载；
**绿色版 zip** 适合不想安装、或想放 U 盘/移动硬盘随插随用（注意整个文件夹一起解压）。
卸载安装版时会移除程序文件，不会删除你的任何配置（配置存在程序目录内）。

**Q: 端口 5000 被占用？**
A: 接收端会明确报错而非静默失败，请关闭其它接收端实例（含旧版本）。

**Q: 头显里找不到装好的 App？**
A: 侧载安装的第三方 App 通常不会出现在主界面的应用列表中，请到
「**库 / 未知来源 / 全部应用**」分类下查找（各机型叫法不同）。

**Q: 安装时报 `INSTALL_FAILED_TEST_ONLY`？**
A: 该包是带 `testOnly` 标记的调试构建。本项目 Release 中的正式包已关闭该标记，
可直接安装；若你自行构建 debug 包，用 `adb install -t` 安装即可。

## 许可

本项目采用 **MIT License**，可自由使用、修改、分发，**包括商业用途**。

**唯一要求**：保留原始版权声明与出处标注（即在你的衍生作品中注明本项目来源）。

详见 [LICENSE](LICENSE)。

> 注意：本项目依赖的第三方库（见上表）各自遵循其原有许可，其中
> **pyvirtualcam 为 GPL-2.0**、**PySide6/Qt 为 LGPL-3.0**，二次分发时请一并遵守。
> 安卓端内嵌的 AndroidUSBCamera / UVCCamera / libcommon 均为 Apache-2.0。

## 致谢

- [saki4510t](https://github.com/saki4510t) — UVCCamera / libcommon
- [jiangdongguo](https://github.com/jiangdongguo) — AndroidUSBCamera
- [OpenIris 项目](https://github.com/loopier/OpenIris) — 流协议与生态兼容参考

---

*本项目为个人 DIY 项目，与上述第三方项目作者无隶属或背书关系。*
