@echo off
rem ============================================================
rem VRCReceiver 一键打包 + 发布
rem   版本号单一口径 = vrc_receiver.py 里的 APP_VERSION
rem   改版本只需改那一行，然后跑这个脚本即可。
rem 产物：
rem   installer\Output\VRCReceiver-Setup-<ver>.exe   （带向导的安装包）
rem   E:\VRCForwarder-release\VRCReceiver-Setup-<ver>.exe
rem   E:\VRCForwarder-release\VRCReceiver-<ver>-win64.zip （免安装版）
rem ============================================================
cd /d %~dp0
for /f %%v in ('python read_version.py') do set VER=%%v
echo === VERSION (from vrc_receiver.py): %VER% ===

echo === [1/4] PyInstaller ===
python -m PyInstaller VRCReceiver.spec --noconfirm

echo === [2/4] Inno Setup ===
"C:\Users\ENJOR-JSHT\AppData\Local\Programs\Inno Setup 6\ISCC.exe" /DMyAppVersion="%VER%" installer\VRCReceiver.iss

echo === [3/4] portable zip ===
powershell -NoProfile -Command "Compress-Archive -Path '%~dp0dist\VRCReceiver\*' -DestinationPath 'E:\VRCForwarder-release\VRCReceiver-%VER%-win64.zip' -Force"

echo === [4/4] copy to release ===
copy /Y "installer\Output\VRCReceiver-Setup-%VER%.exe" "E:\VRCForwarder-release\"
echo DONE VER=%VER%
