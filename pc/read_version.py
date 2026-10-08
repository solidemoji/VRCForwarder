# -*- coding: utf-8 -*-
"""读出 vrc_receiver.py 里的 APP_VERSION（打包脚本用它生成安装包版本，保证单一口径）。"""
import pathlib
import re

src = pathlib.Path(__file__).with_name("vrc_receiver.py").read_text(encoding="utf-8")
m = re.search(r'APP_VERSION\s*=\s*"([^"]+)"', src)
if not m:
    raise SystemExit("未找到 APP_VERSION")
print(m.group(1))
