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
FOLLOW_SYSTEM = False      # True = 语言跟随系统（未手动指定）

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
    "spec_sent": {
        "zh": "已下发规格：{spec}", "en": "Spec sent: {spec}",
        "ja": "仕様を送信：{spec}", "ko": "사양 전송: {spec}", "ru": "Отправлено: {spec}",
    },
    "spec_wait": {
        "zh": "等待设备上报规格…", "en": "Waiting for camera specs…",
        "ja": "カメラ仕様を待機中…", "ko": "카메라 사양 대기 중…",
        "ru": "Ожидание спецификаций…",
    },
    "spec_auto": {
        "zh": "自动（设备未上报实际规格）", "en": "Auto (camera did not report)",
        "ja": "自動（実際の仕様は未報告）", "ko": "자동 (사양 미보고)",
        "ru": "Авто (не сообщено)",
    },
    "spec_no_addr": {
        "zh": "该路还没收到画面，无法下发规格", "en": "No stream from this camera yet",
        "ja": "まだ映像を受信していません", "ko": "아직 영상이 없습니다",
        "ru": "Поток ещё не получен",
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
    "fw_warn_missing": {
        "zh": "防火墙未放行（缺少规则）—— 其它设备会搜不到、也连不上。请点右侧「一键修复」",
        "en": "Firewall rules are missing — other devices cannot discover or connect. Click Fix now",
        "ja": "ファイアウォールの許可設定がありません。他の端末から検出・接続できません。「今すぐ修復」を押してください",
        "ko": "방화벽 허용 규칙이 없습니다. 다른 기기에서 검색·연결할 수 없습니다. '지금 수정'을 누르세요",
        "ru": "Правил брандмауэра нет — другие устройства не найдут и не подключатся. Нажмите «Исправить»",
    },
    "fw_warn_block": {
        "zh": "检测到阻止本程序的防火墙规则：{name} —— 其它设备连不上。请点「一键修复」",
        "en": "A firewall rule blocks this app: {name} — other devices cannot connect. Click Fix now",
        "ja": "このアプリを遮断する規則があります: {name}。「今すぐ修復」を押してください",
        "ko": "이 앱을 차단하는 규칙이 있습니다: {name}. '지금 수정'을 누르세요",
        "ru": "Правило блокирует приложение: {name}. Нажмите «Исправить»",
    },
    "fw_fix_btn": {
        "zh": "一键修复", "en": "Fix now", "ja": "今すぐ修復",
        "ko": "지금 수정", "ru": "Исправить",
    },
    "fw_fixing": {
        "zh": "正在修复…", "en": "Fixing...", "ja": "修復中…",
        "ko": "수정 중…", "ru": "Исправление…",
    },
    "fw_ok": {
        "zh": "防火墙已放行 ✓", "en": "Firewall OK ✓",
        "ja": "ファイアウォール設定 OK ✓", "ko": "방화벽 허용됨 ✓",
        "ru": "Брандмауэр настроен ✓",
    },
    "fw_failed": {
        "zh": "未修复成功：可能取消了管理员确认，或当前账号不是管理员（需手动放行）",
        "en": "Fix failed: admin prompt cancelled or no admin rights (add the rule manually)",
        "ja": "修復できませんでした（管理者確認の取消、または管理者権限なし）",
        "ko": "수정 실패: 관리자 확인 취소 또는 관리자 권한 없음",
        "ru": "Не удалось: отменён запрос администратора или нет прав",
    },
    "tab_receiver": {
        "zh": "接收端", "en": "Receiver", "ja": "受信", "ko": "수신", "ru": "Приём",
    },
    "tab_frame": {
        "zh": "Steam Frame 安装", "en": "Steam Frame Setup",
        "ja": "Steam Frame セットアップ", "ko": "Steam Frame 설치",
        "ru": "Установка Steam Frame",
    },
    "frm_title": {
        "zh": "Steam Frame 侧 VRCForward 安装", "en": "Install VRCForward on Steam Frame",
        "ja": "Steam Frame 側に VRCForward をインストール", "ko": "Steam Frame 측 VRCForward 설치",
        "ru": "Установка VRCForward на Steam Frame",
    },
    "frm_hint": {
        "zh": "头显与 PC 连同一个局域网；输入头显的开机密码（默认用户 steamos）。程序会自动装驱动和发送端，并设为开机自启。",
        "en": "Connect the headset and PC to the same LAN. Enter the headset login password (user: steamos). The driver and sender are installed and enabled at boot automatically.",
        "ja": "ヘッドセットと PC を同じ LAN に接続し、ログインパスワード（ユーザー: steamos）を入力してください。ドライバと送信側を自動で導入し、自動起動に設定します。",
        "ko": "헤드셋과 PC를 같은 LAN에 연결하고 로그인 비밀번호(사용자: steamos)를 입력하세요. 드라이버와 송신기를 자동 설치하고 부팅 시 자동 실행으로 설정합니다.",
        "ru": "Подключите шлем и ПК к одной локальной сети и введите пароль (пользователь: steamos). Драйвер и отправитель установятся и добавятся в автозапуск.",
    },
    "frm_ip": {
        "zh": "头显地址", "en": "Headset IP", "ja": "ヘッドセット IP",
        "ko": "헤드셋 IP", "ru": "IP шлема",
    },
    "frm_user": {
        "zh": "用户名", "en": "User", "ja": "ユーザー名",
        "ko": "사용자 이름", "ru": "Пользователь",
    },
    "frm_user_tip": {
        "zh": "头显的登录用户名，默认 steamos。如果自己改过用户名，请在此填写。",
        "en": "Headset login user name (default: steamos). Change it if you renamed the account.",
        "ja": "ヘッドセットのログインユーザー名（既定: steamos）。変更している場合は入力してください。",
        "ko": "헤드셋 로그인 사용자 이름(기본: steamos). 계정 이름을 바꿨다면 입력하세요.",
        "ru": "Имя пользователя шлема (по умолчанию steamos). Укажите, если меняли.",
    },
    "frm_pwd": {
        "zh": "密码", "en": "Password", "ja": "パスワード",
        "ko": "비밀번호", "ru": "Пароль",
    },
    "frm_pwd_hint": {
        "zh": "头显登录密码（不会保存）", "en": "Headset login password (not saved)",
        "ja": "ログインパスワード（保存されません）", "ko": "로그인 비밀번호(저장 안 함)",
        "ru": "Пароль входа (не сохраняется)",
    },
    "frm_scan": {
        "zh": "自动查找", "en": "Auto-detect", "ja": "自動検出",
        "ko": "자동 찾기", "ru": "Найти",
    },
    "frm_install": {
        "zh": "开始安装", "en": "Install", "ja": "インストール",
        "ko": "설치 시작", "ru": "Установить",
    },
    "frm_idle": {
        "zh": "就绪", "en": "Ready", "ja": "準備完了", "ko": "준비됨",
        "ru": "Готово",
    },
    "frm_scanning": {
        "zh": "正在扫描局域网…", "en": "Scanning LAN...", "ja": "LAN をスキャン中…",
        "ko": "LAN 검색 중…", "ru": "Поиск в локальной сети…",
    },
    "frm_installing": {
        "zh": "正在安装…（请勿关闭）", "en": "Installing... (do not close)",
        "ja": "インストール中…（閉じないでください）",
        "ko": "설치 중…(닫지 마세요)", "ru": "Установка… (не закрывайте)",
    },
    "frm_scan_none": {
        "zh": "没找到设备：确认头显已连同一局域网，或直接输入它的 IP",
        "en": "No device found: check the headset is on the same LAN, or type its IP",
        "ja": "見つかりません。同じ LAN か IP を直接入力してください",
        "ko": "찾지 못했습니다. 같은 LAN인지 확인하거나 IP를 직접 입력하세요",
        "ru": "Не найдено: проверьте одну локальную сеть или введите IP вручную",
    },
    "frm_scan_ok": {
        "zh": "找到 {n} 台：{ip}", "en": "{n} device(s) found: {ip}",
        "ja": "{n} 台見つかりました: {ip}", "ko": "{n}대 발견: {ip}",
        "ru": "Найдено устройств: {n} ({ip})",
    },
    "frm_need_ip": {
        "zh": "请先填头显地址（或点自动查找）", "en": "Enter the headset IP first",
        "ja": "先に IP を入力してください", "ko": "먼저 헤드셋 IP를 입력하세요",
        "ru": "Сначала укажите IP шлема",
    },
    "frm_need_pwd": {
        "zh": "请先填密码", "en": "Enter the password first",
        "ja": "先にパスワードを入力してください", "ko": "먼저 비밀번호를 입력하세요",
        "ru": "Сначала введите пароль",
    },
    "frm_device": {
        "zh": "已连接 {host} · 内核 {kernel} · 驱动{drv}",
        "en": "Connected {host} · kernel {kernel} · driver {drv}",
        "ja": "{host} に接続 · カーネル {kernel} · ドライバ {drv}",
        "ko": "{host} 연결됨 · 커널 {kernel} · 드라이버 {drv}",
        "ru": "Подключено {host} · ядро {kernel} · драйвер {drv}",
    },
    "frm_drv_ok": {
        "zh": "已加载", "en": "loaded", "ja": "読み込み済み",
        "ko": "로드됨", "ru": "загружен",
    },
    "frm_drv_no": {
        "zh": "未安装", "en": "missing", "ja": "未導入",
        "ko": "없음", "ru": "нет",
    },
    "frm_done": {
        "zh": "安装完成，插上相机几秒后出画面", "en": "Done — picture appears a few seconds after plugging in the camera",
        "ja": "完了。カメラを接続すると数秒で映像が出ます", "ko": "완료. 카메라를 연결하면 몇 초 후 영상이 나옵니다",
        "ru": "Готово. Изображение появится через несколько секунд после подключения камеры",
    },
    "frm_fail": {
        "zh": "安装未完成", "en": "Setup did not finish", "ja": "セットアップ未完了",
        "ko": "설치 미완료", "ru": "Установка не завершена",
    },
    "frm_uninstall": {
        "zh": "卸载", "en": "Uninstall",
        "ja": "アンインストール", "ko": "제거",
        "ru": "Удалить",
    },
    "frm_uninstall_confirm": {
        "zh": "只卸载 UVC 驱动与相机转发（服务+脚本），构建环境保留。\n\n确定卸载吗？",
        "en": "This removes the UVC driver and the camera sender (service + scripts). Build tools are kept.\n\nUninstall now?",
        "ja": "UVC ドライバと送信機能（サービス+スクリプト）のみ削除します。ビルド環境は残ります。\n\n実行しますか？",
        "ko": "UVC 드라이버와 카메라 송신(서비스+스크립트)만 제거합니다. 빌드 환경은 유지됩니다.\n\n제거할까요?",
        "ru": "Удаляются драйвер UVC и отправитель (служба и скрипты). Средства сборки остаются.\n\nУдалить?",
    },
    "frm_uninstalling": {
        "zh": "正在卸载…", "en": "Uninstalling...", "ja": "アンインストール中…",
        "ko": "제거 중…", "ru": "Удаление…",
    },
    "frm_uninstall_done": {
        "zh": "已卸载（构建环境保留）", "en": "Uninstalled (build tools kept)",
        "ja": "削除しました（ビルド環境は保持）", "ko": "제거 완료(빌드 환경 유지)",
        "ru": "Удалено (средства сборки сохранены)",
    },
    "frm_purge_env": {
        "zh": "同时卸载构建环境", "en": "Also remove build tools",
        "ja": "ビルド環境も削除", "ko": "빌드 환경도 제거",
        "ru": "Также удалить средства сборки",
    },
    "frm_purge_env_tip": {
        "zh": "勾选后一并移除本程序装过的构建依赖（gcc/make 等）。带共享库的包（curl/zstd/kmod/python）始终保留，避免影响系统。",
        "en": "Also removes build packages this app installed (gcc/make etc.). Library packages (curl/zstd/kmod/python) are always kept.",
        "ja": "本アプリが入れたビルド用パッケージ（gcc/make など）も削除します。共有ライブラリのパッケージ（curl/zstd/kmod/python）は常に残します。",
        "ko": "이 앱이 설치한 빌드 패키지(gcc/make 등)도 제거합니다. 공유 라이브러리 패키지(curl/zstd/kmod/python)는 항상 유지합니다.",
        "ru": "Удаляет пакеты сборки, установленные этим приложением (gcc/make и др.). Пакеты с библиотеками (curl/zstd/kmod/python) сохраняются.",
    },
    "frm_uninstall_confirm_purge": {
        "zh": "将卸载 UVC 驱动与相机转发（服务+脚本），并回收本程序装过的构建环境（gcc/make 等）。\n\n确定卸载吗？",
        "en": "Removes the UVC driver and camera sender (service + scripts), and also the build packages this app installed (gcc/make etc.).\n\nUninstall now?",
        "ja": "UVC ドライバと送信機能（サービス+スクリプト）に加え、本アプリが入れたビルド環境（gcc/make など）も削除します。\n\n実行しますか？",
        "ko": "UVC 드라이버와 카메라 송신(서비스+스크립트)을 제거하고, 이 앱이 설치한 빌드 환경(gcc/make 등)도 회수합니다.\n\n제거할까요?",
        "ru": "Удаляются драйвер UVC и отправитель (служба и скрипты), а также пакеты сборки, установленные приложением (gcc/make и др.).\n\nУдалить?",
    },
    "frm_uninstall_done_purge": {
        "zh": "已卸载（含构建环境）", "en": "Uninstalled (build tools removed)",
        "ja": "削除しました（ビルド環境も削除）", "ko": "제거 완료(빌드 환경 포함)",
        "ru": "Удалено (включая средства сборки)",
    },
    "lang_auto": {
        "zh": "跟随系统", "en": "System", "ja": "システムに従う",
        "ko": "시스템 설정", "ru": "Как в системе",
    },
    "lang_title": {
        "zh": "语言", "en": "Language", "ja": "言語", "ko": "언어", "ru": "Язык",
    },
    "lang_restart": {
        "zh": "语言已保存，程序将自动重启以生效。",
        "en": "Language saved. The app will restart now to apply it.",
        "ja": "言語を保存しました。反映のため再起動します。",
        "ko": "언어가 저장되었습니다. 적용을 위해 다시 시작합니다.",
        "ru": "Язык сохранён. Приложение перезапустится.",
    },
    "hint_auto_ok": {
        "zh": "自动搜索 OK · 已应答 {n} 次", "en": "Auto-detect OK · answered {n}x",
        "ja": "自動検出 OK · 応答 {n} 回", "ko": "자동 검색 OK · 응답 {n}회",
        "ru": "Автоопределение OK · ответов: {n}",
    },
    "hint_auto_waiting": {
        "zh": "自动搜索 OK · 尚未收到查询", "en": "Auto-detect OK · no query yet",
        "ja": "自動検出 OK · 問い合わせ待ち", "ko": "자동 검색 OK · 질의 대기 중",
        "ru": "Автоопределение OK · запросов пока нет",
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


# ---------------------------------------------------------------------
#  语言列表（供界面手动切换） + 远端日志翻译入口
# ---------------------------------------------------------------------
LANGS = ("zh", "en", "ja", "ko", "ru")
LANG_NAMES = {
    "zh": "中文",
    "en": "English",
    "ja": "日本語",
    "ko": "한국어",
    "ru": "Русский",
}


def log_line(line: str) -> str:
    """远端安装脚本日志：中文界面原样输出，其它语言走英文兜底。"""
    if LANG == "zh" or not line:
        return line
    try:
        import log_i18n
        return log_i18n.translate(line)
    except Exception:
        return line
