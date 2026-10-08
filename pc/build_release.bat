@echo off
cd /d %~dp0
rem ============================================================
rem VRCReceiver build + release (KEEP THIS FILE PURE ASCII:
rem Chinese comments break cmd under GBK codepage)
rem   version comes from APP_VERSION in vrc_receiver.py
rem outputs:
rem   installer\Output\VRCReceiver-Setup-<ver>.exe
rem   E:\VRCForwarder-release\VRCReceiver-Setup-<ver>.exe
rem   E:\VRCForwarder-release\VRCReceiver-<ver>-win64.zip
rem ============================================================
for /f %%v in ('python read_version.py') do set VER=%%v
if "%VER%"=="" (
  echo ERROR: cannot read APP_VERSION from vrc_receiver.py
  exit /b 1
)
echo === VERSION: %VER% ===

echo === [1/4] PyInstaller ===
python -m PyInstaller VRCReceiver.spec --noconfirm
if errorlevel 1 exit /b 1

echo === [2/4] Inno Setup ===
"C:\Users\ENJOR-JSHT\AppData\Local\Programs\Inno Setup 6\ISCC.exe" /DMyAppVersion="%VER%" installer\VRCReceiver.iss
if errorlevel 1 exit /b 1

echo === [3/4] portable zip ===
powershell -NoProfile -Command "Compress-Archive -Path '%~dp0dist\VRCReceiver\*' -DestinationPath 'E:\VRCForwarder-release\VRCReceiver-%VER%-win64.zip' -Force"

echo === [4/4] copy to release ===
copy /Y "installer\Output\VRCReceiver-Setup-%VER%.exe" "E:\VRCForwarder-release\"
echo DONE VER=%VER%
