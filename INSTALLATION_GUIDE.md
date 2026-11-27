# MotoVision Pro – Installation & Sideloading Guide
**Target Device:** Motorola Moto G23 (XT2343-1, XT2343-2, XT2343)  
**Operating System:** Android 13 / Android 14 (My UX)  
**Package:** `com.motovision.pro`  

---

## 1. Deliverable APK Packages
MotoVision Pro is built as an independent, standalone Android camera application. It does NOT replace or modify the stock camera firmware, ensuring zero risk to your existing device setup.

- **Release APK (Recommended):**
  - Path: `app/build/outputs/apk/release/app-release.apk`
  - Size: ~5.5 MB
  - SHA-256: `0b4208994331dde3c66cb72f5d652e35ec323117d5363e863c6c6472154cf125`
  - Optimized resource packing, signed with standard release signing configuration.

- **Debug APK (Development & Logcat diagnostics):**
  - Path: `app/build/outputs/apk/debug/app-debug.apk`
  - Size: ~7.7 MB
  - SHA-256: `317852a9e59ab5cd05f9de5ac33d2ae905a9d817383a38cb0afb6462bf65edc2`
  - Includes debugging symbols and detailed console logging.

---

## 2. Step-by-Step Sideloading Instructions

### Method A: Direct Sideloading via USB Transfer
1. Connect your Motorola Moto G23 to your computer using a standard USB-C cable.
2. On your Moto G23, swipe down the notification shade, tap **Charging this device via USB**, and select **File Transfer / Android Auto**.
3. On your computer, copy `app-release.apk` into your phone's internal storage (e.g., the `Download` folder).
4. Disconnect the USB cable safely.
5. Open the **Files by Google** (or your preferred file manager) app on your Moto G23.
6. Navigate to **Downloads** and tap `app-release.apk`.
7. If prompted with *"For your security, your phone is not allowed to install unknown apps from this source"*:
   - Tap **Settings**.
   - Toggle **Allow from this source** to ON.
   - Tap the Back button.
8. Tap **Install**. Once finished, tap **Open**.

### Method B: Direct Download via Web Browser or Cloud Drive
1. Upload `app-release.apk` to your personal Google Drive or preferred local network storage.
2. On your Moto G23, open Chrome or Drive and download the APK.
3. Tap the completed download notification or locate it in the `Download` folder.
4. Allow installation from Chrome/Drive when prompted.
5. Tap **Install**.

---

## 3. Required Permissions Setup
Upon first launching MotoVision Pro, Android runtime permissions will be requested:

1. **Camera Permission (`android.permission.CAMERA`):**
   - **Mandatory.** Allows full access to the Samsung ISOCELL JN1 50MP sensor, 5MP Ultrawide, 2MP Macro, and 16MP Front cameras via CameraX and Camera2 Interop.
   - Select **While using the app**.

2. **Microphone Permission (`android.permission.RECORD_AUDIO`):**
   - **Required for Video Mode.** Enables high-fidelity 48 kHz stereo AAC audio recording.
   - Select **While using the app**.

3. **Storage / Photos and Videos (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`):**
   - **Required.** Allows saving enhanced photographs and high-bitrate videos into `Pictures/MotoVisionPro` and `Movies/MotoVisionPro`, as well as displaying thumbnails in the live gallery viewer.

---

## 4. Recommended Initial Settings for Moto G23

1. **Photo Mode Profile:** Set to **Natural** or **Balanced** (tap the profile chip in the top bar):
   - **Natural:** Disables synthetic sharpening and gives realistic organic colors.
   - **Balanced:** Provides moderate HDR tone mapping, ideal for everyday photography.
2. **Video Mode Bitrate:** Tap the **Bitrate** chip and select **High (38M)**:
   - Eliminates the blocky macroblocking compression artifacts present in the stock Motorola camera.
3. **Electronic Image Stabilization (EIS):** Keep **EIS ON** for hand-held video recordings.
4. **Night Mode:** Hold the phone steady for 1–2 seconds when capturing low-light night scenes to allow multi-frame exposure integration.

---

## 5. Troubleshooting & FAQ

- **"App not installed" Error:**
  - Verify your device has at least 50 MB of free internal storage.
  - If a previous development build was installed with mismatched signing keys, uninstall the older build before installing `app-release.apk`.

- **Where are captured photos and videos stored?**
  - Enhanced Photos: `Internal Storage / Android / data / com.motovision.pro / files / Pictures / MotoVisionPro`
  - High-Bitrate Videos: `Internal Storage / Android / data / com.motovision.pro / files / Movies / MotoVisionPro`
  - RAW Dumps: `Internal Storage / Android / data / com.motovision.pro / files / Pictures / MotoVisionPro_RAW`
