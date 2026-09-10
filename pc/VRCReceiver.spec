# -*- mode: python ; coding: utf-8 -*-
"""VRCReceiver 打包配置 (PyInstaller) —— 瘦身版

构建:
    pyinstaller VRCReceiver.spec --noconfirm --clean

瘦身要点(体积 238MB -> 约 80MB):
  1. **排除完整 opencv-python**(约 98MB)——本程序只用 JPEG 编解码/缩放/色彩转换,
     全部由 cv2_shim.py(Pillow) 提供(与原 cv2 API 一致, 已像素级对拍验证)。
  2. 排除无用 Qt 模块(WebEngine/Quick/QML/3D/Charts/Multimedia/Pdf 等)。
  3. 剔除 Qt 冗余文件: opengl32sw.dll(19.7MB 软件 OpenGL 回退, 本程序用不到)、
     translations(6.4MB 多语言翻译, 界面文案自带 i18n 不经 Qt)、Qt6Quick/Qml 运行库、
     Qt6Pdf、未用到的 imageformats 插件(gif/tiff/webp 等)。
"""
from PyInstaller.utils.hooks import collect_submodules

hiddenimports = collect_submodules("pyvirtualcam") + ["i18n", "vrc_protocol",
                                                      "cv2_shim"]

excludes = [
    "cv2",
    # --- 无用 Qt 模块 ---
    "PySide6.QtWebEngineCore", "PySide6.QtWebEngineWidgets", "PySide6.QtWebEngineQuick",
    "PySide6.QtQuick", "PySide6.QtQuick3D", "PySide6.QtQml", "PySide6.QtQuickWidgets",
    "PySide6.Qt3DCore", "PySide6.Qt3DRender", "PySide6.Qt3DAnimation",
    "PySide6.Qt3DExtras", "PySide6.Qt3DInput", "PySide6.Qt3DLogic",
    "PySide6.QtCharts", "PySide6.QtDataVisualization", "PySide6.QtGraphs",
    "PySide6.QtMultimedia", "PySide6.QtMultimediaWidgets", "PySide6.QtBluetooth",
    "PySide6.QtNfc", "PySide6.QtPositioning", "PySide6.QtLocation",
    "PySide6.QtSensors", "PySide6.QtSerialPort", "PySide6.QtSerialBus",
    "PySide6.QtSql", "PySide6.QtTest", "PySide6.QtDesigner", "PySide6.QtHelp",
    "PySide6.QtPdf", "PySide6.QtPdfWidgets", "PySide6.QtRemoteObjects",
    "PySide6.QtScxml", "PySide6.QtSpatialAudio", "PySide6.QtStateMachine",
    "PySide6.QtTextToSpeech", "PySide6.QtUiTools", "PySide6.QtWebChannel",
    "PySide6.QtWebSockets", "PySide6.QtHttpServer", "PySide6.QtOpenGL",
    "PySide6.QtOpenGLWidgets", "PySide6.QtNetworkAuth", "PySide6.QtSvgWidgets",
    # --- 其他大库/无关库 ---
    "tkinter", "matplotlib", "scipy", "pandas", "IPython", "jupyter",
    "PyQt5", "PyQt6", "PySide2", "yaml", "setuptools", "pip",
]

a = Analysis(
    ["vrc_receiver.py"],
    pathex=[],
    binaries=[],
    datas=[("i18n.py", "."), ("vrc_protocol.py", "."), ("cv2_shim.py", ".")],
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=excludes,
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=None,
    noarchive=False,
)

# ---- 剔除 Qt 冗余二进制/资源(体积大头) ----
# 匹配规则基于目标路径的 basename(大小写不敏感)
_DROP_BASENAMES = {
    # 19.7MB: 无 GPU 时的软件 OpenGL 回退, Qt Widgets 程序不会用到
    "opengl32sw.dll",
    # Quick/QML 运行库(本程序纯 Widgets)
    "qt6quick.dll", "qt6qml.dll", "qt6qmlmodels.dll", "qt6qmlworkerscript.dll",
    "qt6quickwidgets.dll", "qt6quickshapes.dll", "qt6quicktemplates2.dll",
    "qt6quickcontrols2.dll", "qt6quickcontrols2impl.dll", "qt6quicklayouts.dll",
    "qt6quickdialogs2.dll", "qt6quickdialogs2quickimpl.dll", "qt6quickdialogs2utils.dll",
    "qt6quickeffects.dll", "qt6quickparticles.dll", "qt6quicktest.dll",
    "qt6quickshapes.dll", "qt6labsplatform.dll", "qt6labssettings.dll",
    "qt6labsanimation.dll", "qt6labsfolderlistmodel.dll", "qt6labsqmlmodels.dll",
    "qt6labswavefrontmesh.dll", "qt6shadertools.dll",
    # PDF / 其他用不到
    "qt6pdf.dll", "qt6pdfwidgets.dll", "qt6svgwidgets.dll",
    "qt6networkauth.dll", "qt6texttospeech.dll", "qt6spatialaudio.dll",
    "qt6serialport.dll", "qt6serialbus.dll", "qt6sql.dll", "qt6test.dll",
    "qt6help.dll", "qt6designer.dll", "qt6designercomponents.dll",
    "qt6bluetooth.dll", "qt6nfc.dll", "qt6positioning.dll", "qt6sensors.dll",
    "qt6charts.dll", "qt6datavisualization.dll", "qt6graphs.dll",
    "qt6websockets.dll", "qt6webchannel.dll", "qt6httpserver.dll",
    "qt6scxml.dll", "qt6statemachine.dll", "qt6remoteobjects.dll",
    "qt6multimedia.dll", "qt6multimediawidgets.dll", "qt6opengl.dll",
    "qt6openglwidgets.dll", "qt6gamepad.dll",
    # 未用到的图片格式插件(只保留 jpeg/png/ico)
    "qgif.dll", "qtiff.dll", "qwebp.dll", "qpdf.dll", "qsvg.dll",
    "qsvgicon.dll", "qtga.dll", "qwbmp.dll", "qicns.dll", "qjp2.dll",
    "qmng.dll", "qodbc.dll", "qsqlite.dll", "qpsql.dll", "qmysql.dll",
    "qtuiotouchplugin.dll", "qevdevkeyboardplugin.dll", "qevdevmouseplugin.dll",
    "qevdevtouchscreenplugin.dll", "qminimaleglintegration.dll",
    "qwebgl.dll", "qdirect2d.dll",
}


def _keep(entry):
    """True=保留; 依据 basename 与路径片段剔除冗余项"""
    dest = (entry[0] or "").replace("\\", "/").lower()
    base = dest.rsplit("/", 1)[-1]
    if base in _DROP_BASENAMES:
        return False
    # translations 目录整目录剔除(6.4MB, 界面文案走自带 i18n)
    if "/translations/" in dest or dest.endswith("/translations"):
        return False
    # Qt QML 目录(Qt/qml/**)整目录剔除
    if "/qml/" in dest or dest.endswith("/qml"):
        return False
    return True


a.binaries = TOC([e for e in a.binaries if _keep(e)])
a.datas = TOC([e for e in a.datas if _keep(e)])

pyz = PYZ(a.pure, a.zipped_data, cipher=None)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="VRCReceiver",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon="VRCReceiver.ico",
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="VRCReceiver",
)
