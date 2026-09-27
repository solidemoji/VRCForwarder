# VRCForwarder · Project Overview

Wirelessly forward USB camera feeds from a **VR standalone headset / Android phone**
to a **PC** over your local network — for VRChat face tracking and eye tracking software.

**Repository: <https://github.com/solidemoji/VRCForwarder>** ｜
**Download: <https://github.com/solidemoji/VRCForwarder/releases>**

---

## What It Is

An **open-source, two-part** toolkit:

| Component | Runs on | Role |
|---|---|---|
| **VRCForwarder** | VR headset / Android phone | Reads UVC cameras plugged into its own USB port and streams them over WiFi |
| **VRCReceiver** | Windows PC | Receives multiple streams, displays them, and exposes URLs that desktop software can pull directly |

**Typical use case**: an eye-tracking or face-tracking camera inside a standalone VR headset
(XIAO ESP32-S3 module, driver-free UVC camera, etc.) can only be used by the headset itself
and cannot be connected to a PC. This project **bridges the video wirelessly** to the PC so
desktop software such as **Baballonia, OpenIris, or VRCFaceTracking** can consume the stream.

```
┌────────── VR headset / Android phone (VRCForwarder) ──────┐
│  UVC cameras ×N ──USB──> USB Host capture ──WiFi(UDP)──┐  │
└────────────────────────────────────────────────────────┼──┘
                                                         ↓ UDP 5000
┌──────────────────── PC (VRCReceiver) ──────────────────┴──┐
│  Multi-camera panel + one-click copy of each stream URL    │
│  ├─ MJPEG over HTTP bridge (:8080) → Baballonia / OpenCV   │
│  └─ Optional: output to an OBS virtual camera              │
└────────────────────────────────────────────────────────────┘
```

---

## Features

**Headset / phone app**

- Reads UVC cameras directly via USB Host, auto-detecting two transport types:
  bulk (XIAO and similar custom modules) and standard driver-free ISO cameras
- Up to 6 concurrent cameras with hot-plug auto-recovery and per-camera start/stop
- Automatic camera naming, with left/right eye distinguishable for identical models
- **Manual video-spec selection** (format × resolution × frame rate), and it displays
  the spec actually in effect
- Automatic LAN discovery of the PC receiver; UI language follows the system
  (Chinese / English / Japanese / Korean / Russian)

**PC side**

- Multi-camera panel with individual show/hide and a system notification on new streams
- Built-in MJPEG over HTTP bridge (`http://127.0.0.1:8080/cam/<device-name>`),
  in the same format as the OpenIris firmware's WiFi stream, so it is compatible with
  the existing eye-tracking ecosystem
- Optional output to an OBS virtual camera (for software that only understands UVC)
- Ships as a guided installer (choose your own install location) or a portable zip

---

## Device Compatibility

**We have only tested the two devices below on our own hardware. Every other model is
unverified — please download it, try it, and report back.**

| Device | OS | Status |
|---|---|---|
| **Pico 4 Ultra** | PICO OS 5 / Android 14 | ✅ Verified working |
| **Quest 3 / 3S** | Horizon OS / Android 14 | ✅ Verified working (validated on Quest 3S) |
| **Pico 4** | Android 10 | ❓ Untested |
| **Pico 4 Pro** | PICO OS 4 / Android 10 | ❓ Untested |
| **Quest 2** | Horizon OS | ❓ Untested |
| Other Android phones / tablets | Android 9 or newer | ❓ Untested |

**About Pico 4 / 4 Pro**: an **older APK build was tested on one of these and failed**
(the device plug-in is detected, but the camera list stays empty). Our analysis points to a
known Android 10 behaviour — when `targetSdk >= 28`, a UVC device **cannot obtain USB
permission even with the `CAMERA` permission granted**. The current build therefore
**deliberately lowers `targetSdk` to 27** to work around it, but we have **no Android 10
device to verify against**, so it is honestly marked *untested* rather than
supported or unsupported.

> The app's `minSdk` is **28 (Android 9.0)** — systems below that cannot install it.

---

## Requirements

| Item | Requirement |
|---|---|
| Headset / phone OS | **Android 9 or newer**. **Android 14 verified**; Android 10–13 untested |
| Network | Same LAN as the PC (Wi-Fi or wired both fine) |
| USB | Device must support **USB Host (OTG)**; a **powered USB hub** is recommended for multiple cameras |
| PC | Windows 10 / 11, 64-bit |

---

## Download & Install

### Headset / phone

1. **Enable developer mode** on the device, then turn on **USB debugging**
   under Settings → Developer
2. Download `VRCForwarder-*.apk` from the release and **sideload** it:
   - **Pico**: connect over USB, copy the APK to the headset's root folder, then tap it in the file manager
   - **Quest**: install with [SideQuest](https://sidequestvr.com/) or sideload via adb
   - Any device: `adb install -r VRCForwarder-*.apk`

> **This app is not published on any app store — sideloading is the only way to install it.**

**Quest (Horizon OS) users**

- Quest requires an **extra system permission** on first use: `horizonos.permission.USB_CAMERA`.
  When the prompt appears the first time you press **Start forwarding**, **you must tap Allow**
- That permission is **not permitted in Horizon OS store review**, which is why this app
  **must be sideloaded on Quest**
- **Minor note (not a malfunction)**: when you plug in a USB video device, the system may pop up
  an "open with" dialog or a **virtual-display selection window**. You **can tap "Don't allow"**
  or simply close it — it is triggered by the Quest system itself and is unrelated to this app

### PC side

- **Installer (recommended)**: `VRCReceiver-Setup-x.y.z.exe` — follow the wizard, choose your own install location
- **Portable**: `VRCReceiver-win64.zip` — extract the **entire folder** together and run `VRCReceiver.exe`

### Usage

1. Plug the UVC camera into the headset's / phone's USB port (use a powered hub for multiple cameras)
2. Open the app, enter the PC's LAN IP (or tap **Auto-search**), press **Start forwarding**, and allow USB access
3. A camera panel appears on the PC → copy its stream URL with one click → paste it into your desktop software

---

## Repository Layout

```
VRCForwarder/
├── android/     # Headset / phone app (Java + Gradle)
└── pc/          # PC receiver (Python + PySide6)
```

---

## License

Released under the **MIT License** — free to use, modify, and distribute,
**including for commercial purposes**. **The only requirement**: retain the original
copyright notice and attribution.

> Bundled third-party libraries keep their own licenses; please comply with them when
> redistributing (notably pyvirtualcam under GPL-2.0, PySide6/Qt under LGPL-3.0, and
> AndroidUSBCamera / UVCCamera / libcommon under Apache-2.0 on the Android side).

---

*A personal DIY project with limited device coverage — please report your results in Issues.
Not affiliated with or endorsed by any of the third-party projects referenced above.*
