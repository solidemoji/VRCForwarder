# -*- coding: utf-8 -*-
"""frame_tab.py — VRCReceiver 里的 Steam Frame 一键安装选项卡。

复用 frame_core（从原独立安装器抽离出来的逻辑）：
  · scan_lan()        扫局域网里开 SSH 的主机
  · FrameSession      SSH / sudo / 上传
  · Worker            QObject 后台任务（信号 log/stage/progress/device/finished）

密码只存在于内存与本进程，不写日志、不落盘。
"""
import threading

from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton,
    QProgressBar, QPlainTextEdit, QComboBox, QMessageBox, QCheckBox,
)

import frame_core
import i18n
from i18n import t

BG = "#141416"
CARD = "#1b1b1f"
ORANGE = "#ff7a00"


class FrameTab(QWidget):
    scan_done = Signal(list)
    scan_log = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._thread = None
        self._worker = None
        self._busy = False
        self.scan_done.connect(self._on_scan_done)
        self.scan_log.connect(self._append)
        self._build()

    # ---------------- UI ----------------
    def _build(self):
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 14, 16, 12)
        lay.setSpacing(10)

        title = QLabel(t("frm_title"))
        title.setStyleSheet("font-size:17px; font-weight:bold; color:#f2f2f2;")
        lay.addWidget(title)

        hint = QLabel(t("frm_hint"))
        hint.setWordWrap(True)
        hint.setStyleSheet("color:#9aa0a6; font-size:12px;")
        lay.addWidget(hint)

        # 地址行
        row1 = QHBoxLayout()
        row1.addWidget(QLabel(t("frm_ip")))
        self.ipBox = QComboBox()
        self.ipBox.setEditable(True)
        self.ipBox.setMinimumWidth(200)
        self.ipBox.setStyleSheet(
            f"QComboBox{{background:{CARD}; color:#f2f2f2; border:1px solid #333;"
            "border-radius:4px; padding:5px 8px;}}")
        row1.addWidget(self.ipBox, 1)

        row1.addWidget(QLabel(t("frm_user")))
        self.userEdit = QLineEdit(frame_core.DEFAULT_USER)
        self.userEdit.setFixedWidth(120)
        self.userEdit.setPlaceholderText("steamos")
        self.userEdit.setToolTip(t("frm_user_tip"))
        self.userEdit.setStyleSheet(
            f"QLineEdit{{background:{CARD}; color:#f2f2f2; border:1px solid #333;"
            "border-radius:4px; padding:5px 8px;}}")
        row1.addWidget(self.userEdit)

        self.btnScan = QPushButton(t("frm_scan"))
        self.btnScan.clicked.connect(self._on_scan)
        row1.addWidget(self.btnScan)
        lay.addLayout(row1)

        # 密码行
        row2 = QHBoxLayout()
        row2.addWidget(QLabel(t("frm_pwd")))
        self.pwdEdit = QLineEdit()
        self.pwdEdit.setEchoMode(QLineEdit.Password)
        self.pwdEdit.setPlaceholderText(t("frm_pwd_hint"))
        self.pwdEdit.setStyleSheet(
            f"QLineEdit{{background:{CARD}; color:#f2f2f2; border:1px solid #333;"
            "border-radius:4px; padding:5px 8px;}}")
        row2.addWidget(self.pwdEdit, 1)
        self.btnInstall = QPushButton(t("frm_install"))
        self.btnInstall.clicked.connect(self._on_install)
        self.btnInstall.setStyleSheet(
            f"QPushButton{{background:{ORANGE}; color:#1a1a1a; border:0; border-radius:4px;"
            "padding:7px 18px; font-weight:bold;}"
            "QPushButton:disabled{background:#7a5a3a; color:#dcdcdc;}")
        row2.addWidget(self.btnInstall)

        self.btnUninstall = QPushButton(t("frm_uninstall"))
        self.btnUninstall.clicked.connect(self._on_uninstall)
        self.btnUninstall.setCursor(Qt.PointingHandCursor)
        self.btnUninstall.setStyleSheet(
            "QPushButton{background:transparent; color:#ff9a4a; border:1px solid #ff7a00;"
            "border-radius:4px; padding:6px 12px;}"
            "QPushButton:disabled{color:#7a5a3a; border-color:#5a4030;}")
        row2.addWidget(self.btnUninstall)

        self.chkPurge = QCheckBox(t("frm_purge_env"))
        self.chkPurge.setStyleSheet("color:#c8c8c8; font-size:12px;")
        self.chkPurge.setToolTip(t("frm_purge_env_tip"))
        row2.addWidget(self.chkPurge)
        lay.addLayout(row2)

        # 进度
        self.stage = QLabel(t("frm_idle"))
        self.stage.setStyleSheet("color:#ffcf9e; font-size:12px;")
        lay.addWidget(self.stage)
        self.bar = QProgressBar()
        self.bar.setRange(0, 100)
        self.bar.setValue(0)
        self.bar.setTextVisible(False)
        self.bar.setFixedHeight(6)
        self.bar.setStyleSheet(
            "QProgressBar{background:#2a2a2e; border:0; border-radius:3px;}"
            f"QProgressBar::chunk{{background:{ORANGE}; border-radius:3px;}}")
        lay.addWidget(self.bar)

        # 日志
        self.logview = QPlainTextEdit()
        self.logview.setReadOnly(True)
        self.logview.setMaximumBlockCount(600)
        self.logview.setStyleSheet(
            "background:#0d0d0d; color:#a8b0b8; border:1px solid #333; border-radius:4px;"
            'font-family:Consolas,"Courier New",monospace; font-size:12px;')
        lay.addWidget(self.logview, 1)

    # ---------------- 日志 ----------------
    def _append(self, msg: str):
        import time
        self.logview.appendPlainText(f"{time.strftime('%H:%M:%S')}  {msg}")

    # ---------------- 扫描 ----------------
    def _on_scan(self):
        if self._busy:
            return
        self.btnScan.setEnabled(False)
        self.stage.setText(t("frm_scanning"))
        self._append(t("frm_scanning"))

        def work():
            try:
                found = frame_core.scan_lan(cb=self.scan_log.emit)
            except Exception as e:
                self.scan_log.emit(f"扫描失败: {e}")
                found = []
            self.scan_done.emit(found)

        threading.Thread(target=work, daemon=True).start()

    def _on_scan_done(self, found):
        self.btnScan.setEnabled(True)
        self.stage.setText(t("frm_idle"))
        found = [h for h in found if h]
        if not found:
            self._append(t("frm_scan_none"))
            return
        # 路由器/网关也开 22 端口，别默认选中它
        gw = None
        try:
            me = frame_core.local_ipv4()
            if me:
                gw = me.rsplit(".", 1)[0] + ".1"
        except Exception:
            pass
        found.sort(key=lambda h: (2 if h == gw else (1 if "local" in h else 0)))
        cur = self.ipBox.currentText()
        self.ipBox.clear()
        self.ipBox.addItems(found)
        self.ipBox.setCurrentText(cur if cur in found else found[0])
        self._append(t("frm_scan_ok", n=len(found), ip=found[0]))

    # ---------------- 安装 / 卸载 ----------------
    def _on_install(self):
        self._start(frame_core.Worker.do_install, t("frm_installing"))

    def _on_uninstall(self):
        if self._busy:
            return
        purge = self.chkPurge.isChecked()
        tip = t("frm_uninstall_confirm_purge") if purge else t("frm_uninstall_confirm")
        r = QMessageBox.question(
            self, t("frm_uninstall"), tip,
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if r != QMessageBox.Yes:
            return
        self._start(frame_core.Worker.do_uninstall, t("frm_uninstalling"))

    def _start(self, fn, stage_text):
        if self._busy:
            return
        host = self.ipBox.currentText().strip()
        user = self.userEdit.text().strip() or frame_core.DEFAULT_USER
        pwd = self.pwdEdit.text()
        if not host:
            self._append(t("frm_need_ip"))
            return
        if not pwd:
            self._append(t("frm_need_pwd"))
            return

        self._busy = True
        self._op = "uninstall" if fn is frame_core.Worker.do_uninstall else "install"
        self._purge = self.chkPurge.isChecked()
        for b in (self.btnInstall, self.btnUninstall, self.btnScan, self.chkPurge,
                  self.userEdit):
            b.setEnabled(False)
        self.bar.setValue(0)
        self.stage.setText(stage_text)

        self._thread = QThread(self)
        self._worker = frame_core.Worker()
        self._worker.moveToThread(self._thread)
        self._worker.log.connect(self._append)
        self._worker.stage.connect(self._on_stage)
        self._worker.progress.connect(self.bar.setValue)
        self._worker.device.connect(self._on_device)
        self._worker.finished.connect(self._on_finished)
        self._thread.started.connect(self._worker.run, Qt.QueuedConnection)
        # ★ 必须传【未绑定函数】：Worker.run 内部会自己传 self（prepare + run(self, *args)）
        if fn is frame_core.Worker.do_uninstall:
            self._worker.prepare(fn, host, user, pwd, self.chkPurge.isChecked())
        else:
            self._worker.prepare(fn, host, user, pwd)
        self._thread.start()

    def _on_stage(self, text):
        self.stage.setText(text)

    def _on_device(self, info):
        self._append(t("frm_device", host=info.get("hostname", "?"),
                       kernel=info.get("kernel", "?"),
                       drv=t("frm_drv_ok") if info.get("uvc_loaded") else t("frm_drv_no")))

    def _on_finished(self, res):
        self._busy = False
        self.btnInstall.setEnabled(True)
        self.btnUninstall.setEnabled(True)
        self.btnScan.setEnabled(True)
        self.chkPurge.setEnabled(True)
        self.userEdit.setEnabled(True)
        self.pwdEdit.clear()          # 密码用完立即清掉，不留在内存
        if getattr(res, "ok", False):
            self.bar.setValue(100)
            if getattr(self, "_op", "install") == "uninstall":
                oktext = t("frm_uninstall_done_purge") if getattr(self, "_purge", False) \
                    else t("frm_uninstall_done")
            else:
                oktext = t("frm_done")
            self.stage.setText(oktext)
            self._append("✓ " + oktext)
        else:
            self.stage.setText(t("frm_fail"))
            self._append("✗ " + t("frm_fail") + "：" +
                         i18n.log_line(str(getattr(res, "message", ""))))
        if self._thread:
            self._thread.quit()
            self._thread.wait(3000)
            self._thread.deleteLater()
            self._thread = None
            self._worker = None
