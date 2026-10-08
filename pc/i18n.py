# -*- coding: utf-8 -*-
"""i18n.py — VRCReceiver 多语言(跟随系统语言)

支持: zh(中文) / en(英语) / ja(日语) / ko(韩语) / ru(俄语)
其它语言统一回退英语。
用法:
    from i18n import t, detect_lang
    t("btn_copy")            # 按当前语言取文案
    t("new_stream_body", sid=0)
"""
import locale
import os
import sys

LANG = "en"

# 语言包: key -> {lang: text}
STRINGS = {
    # ---- 通用 ----
    "win_title": {
        "zh": "VRCReceiver · 多路接收端", "en": "VRCReceiver · Multi-Stream Receiver",
        "ja": "VRCReceiver · マルチ受信", "ko": "VRCReceiver · 다중 수신",
        "ru": "VRCReceiver · Приёмник",
    },
    "tray_quit": {
        "zh": "退出", "en": "Quit", "ja": "終了", "ko": "종료", "ru": "Выход",
    },
    "starting": {
        "zh": "启动中...", "en": "Starting...", "ja": "起動中...",
        "ko": "시작 중...", "ru": "Запуск...",
    },
    "waiting_frame": {
        "zh": "等待画面...", "en": "Waiting for video...",
        "ja": "映像を待機中...", "ko": "영상 대기 중...", "ru": "Ожидание видео...",
    },
    "frame_closed": {
        "zh": "（画面已关闭）", "en": "(Video closed)", "ja": "（映像停止中）",
        "ko": "(영상 꺼짐)", "ru": "(Видео отключено)",
    },
    "btn_close_view": {
        "zh": "关闭画面", "en": "Close", "ja": "停止", "ko": "끄기", "ru": "Закрыть",
    },
    "btn_show_view": {
        "zh": "显示画面", "en": "Show", "ja": "表示", "ko": "표시", "ru": "Показать",
    },
    "btn_copy": {
        "zh": "复制", "en": "Copy", "ja": "コピー", "ko": "복사", "ru": "Копировать",
    },
    "cam_label": {
        "zh": "摄像头 {sid}", "en": "Camera {sid}", "ja": "カメラ {sid}",
        "ko": "카메라 {sid}", "ru": "Камера {sid}",
    },
    # ---- 状态栏 ----
    "status_listen": {
        "zh": "监听 UDP {port} · 本机 {ip} · MJPEG 桥 http://127.0.0.1:{mj} · 等待摄像头推流...",
        "en": "Listening UDP {port} · Local {ip} · MJPEG bridge http://127.0.0.1:{mj} · waiting for stream...",
        "ja": "UDP {port} 待受 · 本機 {ip} · MJPEG ブリッジ http://127.0.0.1:{mj} · 配信待ち...",
        "ko": "UDP {port} 수신 중 · 로컬 {ip} · MJPEG 브리지 http://127.0.0.1:{mj} · 스트림 대기...",
        "ru": "UDP {port} · Локальный {ip} · MJPEG-мост http://127.0.0.1:{mj} · ожидание потока...",
    },
    "port_bind_fail": {
        "zh": "端口 {port} 绑定失败(可能已被占用): {err}",
        "en": "Failed to bind port {port} (already in use?): {err}",
        "ja": "ポート {port} のバインドに失敗(使用中の可能性): {err}",
        "ko": "포트 {port} 바인딩 실패(사용 중일 수 있음): {err}",
        "ru": "Не удалось занять порт {port} (уже используется?): {err}",
    },
    # ★ 5000 被占用时自动顺延到下一个空闲端口（很多机器上 5000 被系统服务占用）。
    #   顺延后的真实端口会通过「自动搜索」应答告诉 App，用户无需手动设置。
    "port_shifted": {
        "zh": "端口 {want} 已被占用，已自动改用 {got}（App 端会自动识别）",
        "en": "Port {want} is in use — switched to {got} (the app detects this automatically)",
        "ja": "ポート {want} は使用中のため {got} に変更しました（アプリは自動認識します）",
        "ko": "포트 {want} 이(가) 사용 중이라 {got} (으)로 변경했습니다 (앱이 자동 인식)",
        "ru": "Порт {want} занят — переключено на {got} (приложение определит автоматически)",
    },
    "listening_udp": {
        "zh": "监听 UDP {port}，等待摄像头推流...",
        "en": "Listening on UDP {port}, waiting for camera stream...",
        "ja": "UDP {port} を待受中、カメラ配信を待機...",
        "ko": "UDP {port} 수신 중, 카메라 스트림 대기...",
        "ru": "Прослушивание UDP {port}, ожидание потока камеры...",
    },
    "recv_thread_exit": {
        "zh": "接收线程已退出", "en": "Receiver thread stopped",
        "ja": "受信スレッド終了", "ko": "수신 스레드 종료", "ru": "Поток приёма остановлен",
    },
    "new_stream_title": {
        "zh": "VRCReceiver · 新画面接入", "en": "VRCReceiver · New stream",
        "ja": "VRCReceiver · 新しい映像", "ko": "VRCReceiver · 새 스트림",
        "ru": "VRCReceiver · Новый поток",
    },
    "new_stream_body": {
        "zh": "检测到摄像头 {sid} 推流，已自动显示。\n可在面板上手动关闭画面。",
        "en": "Camera {sid} stream detected and displayed.\nYou can hide it on the panel.",
        "ja": "カメラ {sid} の配信を検出しました。\nパネルで手動停止できます。",
        "ko": "카메라 {sid} 스트림 감지됨.\n패널에서 끌 수 있습니다.",
        "ru": "Обнаружен поток камеры {sid}.\nМожно скрыть на панели.",
    },
    "new_stream_status": {
        "zh": "检测到新推流: 摄像头 {sid}", "en": "New stream detected: camera {sid}",
        "ja": "新しい配信を検出: カメラ {sid}", "ko": "새 스트림 감지: 카메라 {sid}",
        "ru": "Обнаружен новый поток: камера {sid}",
    },
    # ---- 统计 ----
    "stats_line": {
        "zh": "{fps} fps · {mbps} Mbps · 迟到{late} 坏包{bad}",
        "en": "{fps} fps · {mbps} Mbps · late {late} bad {bad}",
        "ja": "{fps} fps · {mbps} Mbps · 遅延{late} 破損{bad}",
        "ko": "{fps} fps · {mbps} Mbps · 지연{late} 손실{bad}",
        "ru": "{fps} fps · {mbps} Mbps · задержка {late} сбой {bad}",
    },
    # ---- 虚拟摄像头 ----
    "vcam_start": {
        "zh": "虚拟摄像头已启动: {dev} {w}x{h}@{fps}fps（摄像头 {sid}）",
        "en": "Virtual camera started: {dev} {w}x{h}@{fps}fps (camera {sid})",
        "ja": "仮想カメラ起動: {dev} {w}x{h}@{fps}fps（カメラ {sid}）",
        "ko": "가상 카메라 시작: {dev} {w}x{h}@{fps}fps (카메라 {sid})",
        "ru": "Виртуальная камера запущена: {dev} {w}x{h}@{fps}fps (камера {sid})",
    },
    "vcam_error": {
        "zh": "虚拟摄像头异常: {err}", "en": "Virtual camera error: {err}",
        "ja": "仮想カメラ エラー: {err}", "ko": "가상 카메라 오류: {err}",
        "ru": "Ошибка виртуальной камеры: {err}",
    },
    # ---- MJPEG 桥 ----
    "bridge_ready": {
        "zh": "MJPEG 桥已启动: http://127.0.0.1:{port}（每路 /cam/<设备名>）",
        "en": "MJPEG bridge ready: http://127.0.0.1:{port} (per-stream /cam/<name>)",
        "ja": "MJPEG ブリッジ起動: http://127.0.0.1:{port}（各 /cam/<名前>）",
        "ko": "MJPEG 브리지 시작: http://127.0.0.1:{port} (스트림별 /cam/<이름>)",
        "ru": "MJPEG-мост запущен: http://127.0.0.1:{port} (по потоку /cam/<имя>)",
    },
    "no_stream": {
        "zh": "暂无推流", "en": "No active stream", "ja": "配信なし",
        "ko": "스트림 없음", "ru": "Нет потоков",
    },
    # ---- 启动后的中央提示（尚无画面时显示：同一局域网 + 要填的 IP/端口）----
    "hint_msg": {
        "zh": "确保 VR 和 PC 处于同一局域网内，并将此 IP 和端口号填入 VR 端 App 中，或者使用 App 内的自动搜索功能",
        "en": "Make sure the VR and PC are on the same LAN, then enter this IP and port in the VR app — or use Auto-detect in the app.",
        "ja": "VR と PC が同じ LAN にあることを確認し、この IP とポートを VR 側アプリに入力してください（アプリ内の「自動検索」も使えます）。",
        "ko": "VR과 PC가 같은 LAN에 있는지 확인한 뒤, 이 IP와 포트를 VR 앱에 입력하세요(앱의 '자동 검색'도 사용 가능).",
        "ru": "Убедитесь, что VR и ПК в одной локальной сети, затем введите этот IP и порт в приложении VR (или «Автопоиск»).",
    },
    # ---- MJPEG 拉流端口冲突（原先静默失败，客户表现为"面板有地址但打不开"）----
    "bridge_port_shifted": {
        "zh": "拉流端口 {want} 已被占用，已自动改用 {got}。\n\n面板上的拉流地址已按新端口显示，可直接使用。\n（若占用者是另一个 VRCReceiver 实例，建议关掉它）",
        "en": "Pull-stream port {want} was busy, so port {got} is used instead.\n\nThe panel already shows URLs with the new port; they work as-is.\n(If another VRCReceiver instance is holding {want}, consider closing it.)",
        "ja": "配信ポート {want} は使用中のため、代わりに {got} を使用します。\n\nパネルのアドレスは新しいポートで表示されています。\n（別の VRCReceiver が {want} を使用している場合は終了を推奨）",
        "ko": "스트림 포트 {want} 이(가) 사용 중이라 {got} 으로 대체했습니다.\n\n패널의 주소는 새 포트로 표시되며 그대로 사용하면 됩니다.\n(다른 VRCReceiver 인스턴스가 {want} 을(를) 점유 중이면 종료를 권장)",
        "ru": "Порт {want} занят, используется {got}.\n\nАдреса на панели уже показаны с новым портом.\n(Если {want} занят другим экземпляром VRCReceiver, закройте его.)",
    },
    "bridge_port_busy": {
        "zh": "拉流端口 {port} 及后续 10 个端口都被占用，拉流地址将不可用！\n\n常见原因：另一个 VRCReceiver 实例已在运行，或该端口被其它软件占用。\n请关闭占用者后重启本程序。",
        "en": "Pull-stream port {port} and the next 10 ports are all busy — stream URLs will NOT work!\n\nCommon cause: another VRCReceiver instance is running, or another app holds the port.\nClose it and restart this program.",
        "ja": "配信ポート {port} 以降の 10 ポートが全て使用中です。配信 URL は使えません。\n\n別の VRCReceiver が動作中か、他のソフトがポートを占有しています。\n終了してから再起動してください。",
        "ko": "스트림 포트 {port} 및 이후 10개 포트가 모두 사용 중이라 스트림 주소를 쓸 수 없습니다!\n\n다른 VRCReceiver 인스턴스가 실행 중이거나 다른 프로그램이 점유 중입니다.\n종료 후 다시 실행하세요.",
        "ru": "Порт {port} и следующие 10 портов заняты — адреса потоков работать НЕ будут!\n\nВероятно, уже запущен другой экземпляр VRCReceiver или порт занят другой программой.\nЗакройте его и перезапустите программу.",
    },
}


def _norm(lang):
    """归一化语言代码: zh-CN -> zh, en_US -> en; 不支持的返回 en"""
    if not lang:
        return "en"
    code = lang.replace("-", "_").split("_")[0].strip().lower()
    return code if code in ("zh", "en", "ja", "ko", "ru") else "en"


def detect_lang():
    """跟随系统语言；识别不到或用不支持的语言时回退英语"""
    cands = []
    try:
        cands.append(locale.getdefaultlocale()[0])
    except Exception:
        pass
    for env in ("LANG", "LC_ALL", "LC_MESSAGES", "LANGUAGE"):
        v = os.environ.get(env)
        if v:
            cands.append(v)
    try:
        import ctypes
        buf = ctypes.create_unicode_buffer(85)
        # Windows: GetUserDefaultLocaleName -> zh-CN / en-US ...
        if ctypes.windll.kernel32.GetUserDefaultLocaleName(buf, 85):
            cands.append(buf.value)
    except Exception:
        pass
    for c in cands:
        code = _norm(c)
        if code != "en" or (c and "en" in (c or "").lower()):
            return code
    return "en"


def set_lang(lang):
    global LANG
    LANG = _norm(lang)


def t(key, **kw):
    """取当前语言文案; 缺失则回退英语, 再缺失返回 key 本身"""
    entry = STRINGS.get(key)
    if not entry:
        return key
    text = entry.get(LANG) or entry.get("en") or key
    if kw:
        try:
            return text.format(**kw)
        except Exception:
            return text
    return text


def init(lang=None):
    """初始化: 显式语言 > 跟随系统 > 英语兜底"""
    set_lang(lang if lang else detect_lang())
    return LANG
