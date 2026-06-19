# Pixeldrain — Native Android Wrapper

A fast, gorgeous, single-purpose Android app that wraps
[pixeldrain.com](https://pixeldrain.com) in a hardened `WebView` but **feels like
a real native app**: animated splash, edge-to-edge Material 3 (Material You)
theming, system-grade downloads, native file/camera uploads, pull-to-refresh, a
beautiful offline screen, and rock-solid back navigation.

---

## ✨ Features

| Area | What you get |
| --- | --- |
| **Engine** | Platform `android.webkit.WebView` — JS, DOM storage, database, pinch-zoom (no on-screen controls), wide viewport, mixed-content media, desktop-grade User-Agent, disk cache for instant warm starts. |
| **Downloads** | `setDownloadListener` → system `DownloadManager`. Saves to the public **Downloads** folder (scoped-storage safe), shows a progress notification, parses RFC 5987 + plain `Content-Disposition` filenames, carries WebView auth cookies, and confirms with a snackbar. |
| **Uploads** | `WebChromeClient.onShowFileChooser` opens the native picker — multiple files, all MIME types — plus a **camera capture** path when the page requests `image/*` with capture. |
| **Navigation** | Gesture/hardware **Back** walks WebView history, then "press back again to exit". `mailto:`, `tel:`, `intent:` and off-site links open in the proper external app. |
| **State** | Slim animated top progress bar, **offline screen** with retry + auto-reconnect, full WebView state restore across rotation and process death. |
| **Design** | Material 3 + dynamic color (Android 12+), curated indigo/violet fallback palette, automatic light/dark, full edge-to-edge with transparent system bars, AndroidX animated splash, adaptive launcher icon (foreground/background/**monochrome**), subtle haptics. |

---

## 🗂 Project structure

```
Pixel-drain-wrapper/
├── settings.gradle.kts
├── build.gradle.kts                 # root: plugin versions
├── gradle.properties
├── gradlew / gradlew.bat            # Gradle wrapper scripts
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── README.md
└── app/
    ├── build.gradle.kts             # module config, deps
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/pixeldrain/wrapper/
        │   ├── PixeldrainApp.kt      # notif channel + dynamic color
        │   ├── MainActivity.kt       # WebView host & all logic
        │   ├── DownloadHelper.kt     # DownloadManager + filename parsing
        │   └── ConnectivityObserver.kt
        └── res/
            ├── drawable/
            │   ├── ic_launcher_background.xml
            │   ├── ic_launcher_foreground.xml
            │   ├── ic_launcher_monochrome.xml
            │   ├── ic_launcher_legacy.xml      # API 24–25 fallback
            │   ├── ic_offline.xml
            │   ├── ic_retry.xml
            │   ├── progress_horizontal.xml
            │   └── splash_logo.xml
            ├── layout/activity_main.xml
            ├── mipmap-anydpi-v24/      # legacy launcher icons
            ├── mipmap-anydpi-v26/      # adaptive launcher icons
            ├── values/
            │   ├── colors.xml
            │   ├── dimens.xml
            │   ├── strings.xml
            │   └── themes.xml
            ├── values-night/
            │   ├── colors.xml
            │   └── themes.xml
            └── xml/
                ├── network_security_config.xml
                ├── file_paths.xml
                ├── data_extraction_rules.xml
                └── backup_rules.xml
```

---

## 🚀 Open in Android Studio

1. **Android Studio** Hedgehog (2023.1.1) or newer.
2. `File ▸ Open…` → select the project root (`Pixel-drain-wrapper`).
3. Let Gradle sync. It downloads the Android Gradle Plugin 8.5.2, Gradle 8.7,
   and the AndroidX/Material dependencies (first sync needs internet).
4. Pick a device/emulator (API 24+) and press ▶ **Run**.

> The Gradle wrapper jar is included, so the command line works out of the box.

---

## 📦 Build an installable APK

**From the command line:**

```bash
# Debug APK (installable, no signing needed)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK (configure signing for a Play-ready build)
./gradlew assembleRelease
```

**Install onto a connected phone:**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**From Android Studio:** `Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)`,
then click **locate** in the toast to reveal the file.

---

## 🔧 Change the loaded URL / branding

- **URL:** edit the `resValue("string", "base_url", "https://pixeldrain.com")`
  line in `app/build.gradle.kts` (single source of truth, read at runtime).
- **App name:** `app/src/main/res/values/strings.xml` → `app_name`.
- **Colors / brand palette:** `res/values/colors.xml` (+ `values-night`).
- **Launcher icon:** swap the vectors in `res/drawable/ic_launcher_*` — the
  droplet foreground, the gradient background, and the monochrome silhouette.
- **Splash:** `res/drawable/splash_logo.xml` and the `Theme.Pixeldrain.Splash`
  block in `res/values/themes.xml`.
- **Allowed in-app domains:** the `handleExternalUrl()` check in
  `MainActivity.kt` keeps `pixeldrain.com` (and subdomains) in-app and pushes
  everything else to external apps.

---

## ⚡ 5-step quick start (build the APK)

1. Install **Android Studio** and open this project folder; wait for Gradle sync.
2. Connect your phone with **USB debugging** on (or start an emulator, API 24+).
3. Run `./gradlew assembleDebug` (or press ▶ in the IDE).
4. Grab `app/build/outputs/apk/debug/app-debug.apk`.
5. `adb install -r app-debug.apk` — or copy the APK to your phone and tap it
   (allow "install unknown apps").

---

## 🎁 Five optional premium upgrades

1. **In-app download manager screen** — a native list of downloads with
   progress, pause/resume, open, and share, backed by `DownloadManager` queries.
2. **Biometric app lock** — `BiometricPrompt` gate on launch/resume for private
   file libraries.
3. **Share-to-upload target** — register a `SEND`/`SEND_MULTIPLE` intent filter
   so other apps can share files straight into a Pixeldrain upload.
4. **Custom native bottom navigation** — Home / Upload / My Files / Settings tabs
   that drive the WebView, for a fully app-like shell.
5. **Account & API-key support** — secure `EncryptedSharedPreferences` storage of
   the Pixeldrain API key with native quick-actions (copy link, delete, stats).

---

*Built with Kotlin, AndroidX, Material 3, and the platform WebView. No
third-party WebView libraries.*
