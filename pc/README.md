# VRCReceiver — PC 端接收程序

完整说明见仓库根目录 [README.md](../README.md)。

## 快速使用

**方式 A：安装包（推荐，免安装 Python）**

下载 [Releases](../../../releases) 里的 `VRCReceiver-Setup-x.y.z.exe`，双击运行，
按向导选择安装位置即可。安装包提供：

- **自选安装目录**（向导里可改）
- **桌面快捷方式**（可选，默认创建）
- **开始菜单项**（含卸载入口）
- **开机自启**（可选，接收端常驻时勾选）

**方式 B：绿色版 zip**

下载 `VRCReceiver-win64.zip`，解压到任意位置，双击 `VRCReceiver.exe`。
（exe 依赖同目录的 `_internal`，**必须整个文件夹一起解压**）

**方式 C：源码运行**
```bash
pip install pyside6 numpy          # 开发用 opencv-python；打包版用 cv2_shim(Pillow) 替代
python vrc_receiver.py
```

## 文件说明

| 文件 | 作用 |
|---|---|
| `vrc_receiver.py` | 主程序：多路画面 UI + MJPEG over HTTP 桥 + 可选虚拟摄像头输出 |
| `vrc_protocol.py` | UDP 协议解析 / 分片重组 / 局域网设备发现（beacon 应答） |
| `i18n.py` | 界面多语言：中 / 英 / 日 / 韩 / 俄，其它语言回退英语 |
| `cv2_shim.py` | 轻量图像层（Pillow 实现 OpenCV 用到的子集），使打包体积减少约 98 MB |
| `VRCReceiver.spec` | PyInstaller 打包配置（已做 Qt 冗余文件裁剪） |
| `VRCReceiver.ico` | 程序图标 |

## 命令行参数

```
--port 5000                 接收端口（默认 5000）
--lang zh|en|ja|ko|ru       界面语言（默认跟随系统）
--mjpeg-port 8080           MJPEG 桥端口（0=禁用）
--mjpeg-fps 60              桥输出帧率
--mjpeg-max-width 0         桥输出等比缩放宽度（0=原尺寸）
--display-fps 60            界面显示帧率（0=不限）
--vcam off|auto|<n>         输出到 OBS 虚拟摄像头（默认关闭）
--help                      查看全部参数
```

## 给桌面软件拉流

| 地址 | 用途 |
|---|---|
| `http://127.0.0.1:8080/` | 浏览器打开，列出所有摄像头与地址 |
| `http://127.0.0.1:8080/video` | 首路流（填裸 IP 亦可） |
| `http://127.0.0.1:8080/cam/<设备名>` | 指定某路（界面面板可一键复制） |
| `http://127.0.0.1:8080/list` | JSON 列出当前所有流 |

## 打包 exe

```bash
pip install pyinstaller pillow pyside6 numpy
pyinstaller VRCReceiver.spec --noconfirm --clean
# 产物: dist/VRCReceiver/VRCReceiver.exe（连同 _internal 目录一起分发）
```
