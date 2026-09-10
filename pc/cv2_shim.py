# -*- coding: utf-8 -*-
"""cv2_shim.py — 轻量 cv2 兼容层（用 Pillow 实现 GUI 实际用到的子集）

动机：完整 opencv-python 约 98MB（cv2.pyd 71MB + ffmpeg dll 27MB），
而本接收端只用到 JPEG 解码/编码、等比缩放、BGR<->RGB —— 全部是 Pillow 就能做的
基础图像操作。用本模块替换后打包体积大幅下降（见 VRCReceiver.spec）。

用法（与 cv2 完全相同的调用方式）：
    import cv2_shim as cv2
    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)   # BGR ndarray
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 80])
    small = cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA)
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

注意：仅实现本项目的用到的子集；窗口显示类 API(imshow/namedWindow/waitKey)
提供空实现以避免旧 CLI 代码 import 失败。
"""
import io

import numpy as np
from PIL import Image

# ---- 常量（取值与 cv2 保持一致，便于直接替换）----
IMREAD_COLOR = 1
IMREAD_GRAYSCALE = 0
IMREAD_UNCHANGED = -1
IMWRITE_JPEG_QUALITY = 1
IMWRITE_PNG_COMPRESSION = 16
INTER_NEAREST = 0
INTER_LINEAR = 1
INTER_AREA = 3
INTER_CUBIC = 2
COLOR_BGR2RGB = 4
COLOR_RGB2BGR = 4
COLOR_BGR2GRAY = 6
COLOR_GRAY2BGR = 8
WINDOW_AUTOSIZE = 1

_BILINEAR = Image.BILINEAR
_NEAREST = Image.NEAREST
_BICUBIC = Image.BICUBIC
_LANCZOS = Image.LANCZOS
_JPG_ALIASES = (".jpg", ".jpeg", ".JPG", ".JPEG", "jpg", "jpeg")


def _to_pil(img):
    """BGR ndarray -> PIL(RGB)"""
    if img.ndim == 2:                       # 灰度
        return Image.fromarray(img, mode="L")
    return Image.fromarray(img[:, :, ::-1])  # BGR -> RGB


def _from_pil(pim):
    """PIL -> BGR ndarray（灰度保持 2 维）"""
    arr = np.asarray(pim)
    if arr.ndim == 2:
        return np.ascontiguousarray(arr)
    return np.ascontiguousarray(arr[:, :, ::-1])


def imdecode(buf, flags=IMREAD_COLOR):
    """JPEG/PNG bytes -> BGR ndarray；失败返回 None（与 cv2 一致）"""
    try:
        data = bytes(buf) if not isinstance(buf, (bytes, bytearray)) else buf
        pim = Image.open(io.BytesIO(data))
        if flags == IMREAD_COLOR:
            pim = pim.convert("RGB")
        elif flags == IMREAD_GRAYSCALE:
            pim = pim.convert("L")
        else:
            pim = pim.convert("RGBA") if pim.mode == "P" else pim
        return _from_pil(pim)
    except Exception:
        return None


def imencode(ext, img, params=None):
    """BGR ndarray -> (True, bytes)；失败 (False, empty)（与 cv2 一致）"""
    try:
        quality = 80
        compression = 6
        if params:
            for i in range(0, len(params) - 1, 2):
                if int(params[i]) == IMWRITE_JPEG_QUALITY:
                    quality = int(params[i + 1])
                elif int(params[i]) == IMWRITE_PNG_COMPRESSION:
                    compression = int(params[i + 1])
        pim = _to_pil(img)
        out = io.BytesIO()
        if str(ext).lower() in _JPG_ALIASES:
            pim.save(out, format="JPEG", quality=quality, subsampling=0)
        else:
            pim.save(out, format="PNG", compress_level=max(0, min(9, compression)))
        return True, np.frombuffer(out.getvalue(), np.uint8)
    except Exception:
        return False, np.empty(0, np.uint8)


def resize(src, dsize, dst=None, fx=0, fy=0, interpolation=INTER_LINEAR):
    """缩放（支持 dsize=(w,h) 或 fx/fy 比例）"""
    h, w = src.shape[:2]
    if dsize and dsize[0] and dsize[1]:
        tw, th = int(dsize[0]), int(dsize[1])
    else:
        tw, th = max(1, int(w * fx)), max(1, int(h * fy))
    mode = {INTER_NEAREST: _NEAREST, INTER_LINEAR: _BILINEAR,
            INTER_CUBIC: _BICUBIC, INTER_AREA: _LANCZOS}.get(interpolation, _BILINEAR)
    return _from_pil(_to_pil(src).resize((tw, th), mode))


def cvtColor(src, code, dst=None):
    """仅支持 BGR<->RGB（本项目所需）"""
    if code in (COLOR_BGR2RGB, COLOR_RGB2BGR):
        return np.ascontiguousarray(src[:, :, ::-1])
    if code == COLOR_BGR2GRAY:
        return np.ascontiguousarray(
            np.asarray(_to_pil(src).convert("L")))
    if code == COLOR_GRAY2BGR:
        return np.ascontiguousarray(np.stack([src] * 3, axis=-1))
    raise NotImplementedError(f"cvtColor code {code} 未在 shim 中实现")


# ---- 窗口显示类：空实现（旧 CLI 预览用；GUI 主程序不使用）----
def namedWindow(*a, **k):
    pass


def imshow(*a, **k):
    pass


def waitKey(*a, **k):
    return -1


def destroyWindow(*a, **k):
    pass


def destroyAllWindows(*a, **k):
    pass


__version__ = "0.1.0-shim"
