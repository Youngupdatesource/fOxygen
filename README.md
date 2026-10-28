<p align="center">
  <img src="docs/assets/logo.png" alt="fOxygen logo" width="128">
</p>

<h1 align="center">fOxygen</h1>

> **fOxygen** is a modified fork of **[AxManager](https://github.com/fahrez182/AxManager)** by **fahrez182**: a self-contained, ADB/Root-powered environment manager for Android with plugins, a WebUI, a permission manager built on the Shizuku API, and a terminal shell (axsh) that works from any terminal app.

[Switch to Chinese translation 切换到中文翻译](README_cn.md) *(upstream AxManager translation, not updated for fOxygen)*

| | |
| --- | --- |
| App name | fOxygen |
| Package | `com.foxygen.manager` |
| Version | 1.2 |
| Modes | ADB (Wireless Debugging / TCP) and Root |
| License | Apache License 2.0 |

## 🍴 About this fork

fOxygen is a fork of AxManager, which is created and maintained by [fahrez182](https://github.com/fahrez182). The core idea, architecture, and the vast majority of the code come from the original project, and all credit for that work belongs to its author. The project was previously published as AxM-Next.

- Original repository: <https://github.com/fahrez182/AxManager>
- Original author: [fahrez182](https://github.com/fahrez182)
- fOxygen maintainer: [Youngupdatesource (RelJawa)](https://github.com/Youngupdatesource)

> **Important:** if you redistribute or modify this project, please keep attribution to the original developer, fahrez182, and retain the [Apache License 2.0](LICENSE).

## 💡 The concept

AxManager explores a dedicated **ADB environment** on Android: instead of being a simple command runner, it keeps a resident, privileged layer (the `axeron_server` daemon) that apps and plugins can talk to. fOxygen keeps that foundation and focuses on a lighter, more stable, and more polished experience.

## ✨ Features

### Inherited from AxManager
- 🏗️ **Internal ADB environment**: a self-contained environment that maintains and uses ADB-level privileges.
- 🖥️ **Shell executor**: run shell commands with persistent sessions, via ADB (non-root) or optionally root.
- ⚡ **Plugins (unrooted modules)**: manage third-party modules without root. [Learn more](https://fahrez182.github.io/AxManager/plugin/what-is-plugin.html)
- 🌐 **WebUI**: manage the environment and plugins through a web-based interface.
- 🔐 **Permission manager** built on the Shizuku API.

### What fOxygen changes

**Terminal (axsh)**
- A native client (`libaxsh.so`) gives any terminal app a full interactive shell through the server: real PTY, `vi`, `top`, job control and window resizing.
- One path for both modes: the client starts a small bootstrap in the app's own process, which asks the manager for the server binder (`AxRuntime.REQUEST_BINDER`) and starts the shell through it. Root and ADB behave the same.
- Started from the **Privilege Manager**: apps that bundle `libtermux.so` get a terminal button. It writes the launcher to `/sdcard/axeron/axsh`, copies `sh /sdcard/axeron/axsh` to the clipboard and opens the app. If the app declares `MANAGE_EXTERNAL_STORAGE`, it is allowed first.
- **Rootless debugging** (root mode only, per app, switchable at runtime): runs that app's axsh sessions at the shell level (uid 2000, like `adb shell`) instead of root, using the bundled `libaxdrop.so` helper. If the helper cannot run, the session is refused instead of falling back to root.

**Privilege Manager**
- Filter by apps that use the Shizuku API, apps that bundle `libtermux.so`, or both (an app matching either shows up). Apps that are switched on are pinned to the top.
- Switching an app on force-stops it, so its permission request is triggered again on the next launch.

**UI / UX**
- Rebranded as **fOxygen**, with new launcher icons and a new maintainer profile.
- **Status card banner** with your own image (JPG, PNG, WebP, or animated GIF, up to 8 MB) at a **2:1** ratio, configurable in *Appearance*. After picking an image you can drag it to choose which part of it is shown.
- Stat tiles and a device info card (device, short kernel version, Android version, ABI, SELinux context).
- Swipe left or right to switch between tabs, plus reworked navigation transitions.
- **Plugin WebUI shortcuts, customizable:** long-press a plugin card to open a centered configuration dialog. Choose the shortcut name and icon (default, the plugin's banner, or your own image). Icons are built as *adaptive icons*, so your launcher decides the final shape.

**WebUI and API**
- Follows the KernelSU WebUI native API (`ksu.exec`, `ksu.spawn`, `ksu.toast`, `ksu.fullScreen`, `ksu.moduleInfo`, `ksu.listPackages(type)`, `ksu.getPackagesInfo`). The old `listAllPackages` / `listUserPackages` / `listSystemPackages` duplicates were removed.
- Callback variants of `exec` and `spawn` no longer block the page. `spawn` reports `error` before `exit`.
- Every process started from a plugin's WebUI or Action carries `AXERON_PLUGIN=<plugin id>`, and plugin installs get their own temporary folder.
- The bundled `test_function` plugin exercises all of this from the WebUI.

**Performance**
- App icon loading is throttled (at most three in parallel), uses fixed-size requests, and keeps a bounded 16 MB memory cache that is cleared when the app goes to the background.
- Search keys (including Pinyin) are computed once instead of on every keystroke.
- The permission list skips system apps early and loads in parallel.
- WebUI icons use a bounded cache, recycled bitmaps, smaller images, and HTTP cache headers.
- Network client is created lazily.

**Reliability and power**
- **Restart that comes back:** the starter runs detached from the server it replaces (output goes to `/data/local/tmp/axeron_restart.log`) and ignores a broken pipe, and the manager starts the server again by the last launch mode if it does not return within about 20 seconds.
- **No wake lock, no idle exemption:** the server never takes a wake lock and neither app touches Doze (no `deviceidle` whitelist, no temporary whitelist, no battery-optimization request). The manager only keeps the screen on while Flash, Quick Shell or a plugin action is running, so the device can enter idle and deep sleep as early as the system allows.
- **Server Guard:** the manager checks the server periodically (a WorkManager job every 30 minutes; the system runs it in its own idle windows) and restarts it with exponential backoff if it stops unexpectedly. Intentional shutdowns and restarts from the app are respected, and auto-restart can be turned off with the `server_guard_auto_restart` setting.
- **Daemon Guard:** on every server start, the daemon only logs diagnostics (cgroup, `oom_score_adj`, adbd state). It changes no power setting.
- The server retries the manager-app lookup before exiting, so a transient failure no longer kills the daemon.
- The plugin igniter prefers the phone's own tools (toybox) and only falls back to BusyBox when a tool is missing. Scripts that declare `ASH_STANDALONE=1` still run on BusyBox.

**Quick Shell**
- A terminal-style output engine built for floods such as `dumpsys`: output is parsed off the main thread and the screen refreshes at most about 30 times per second.
- Lines are rebuilt from the raw stream, so chunk boundaries never split a line, and `\r` progress bars overwrite their own line.
- The last 20,000 lines stay in memory. An *unlimited lines* option keeps every line, spooled to a file on disk.
- View options shared by every command-output screen: line numbers and text size.

## 🔧 Build & Install

fOxygen no longer relies on git history for versioning: the version is fixed at **1.2** (`versionCode` comes from the shared `api` manifest). The application id is `com.foxygen.manager`; the Kotlin namespace stays `frb.axeron.manager` for compatibility with the upstream API.

### Requirements
- JDK 21
- Android SDK platform 36, build-tools 36.0.0
- NDK 29.0.14206865 and CMake 3.22.1
- Git (the `api` folder is a submodule, so clone recursively)

### Build locally

```bash
git clone --recursive https://github.com/Youngupdatesource/fOxygen.git
cd fOxygen
```

Create a keystore once and describe it in `local.properties` (never commit either file):

```properties
sdk.dir=/path/to/android/sdk
signing.storeFile=/path/to/foxygen.jks
signing.storePassword=your-store-password
signing.keyAlias=your-alias
signing.keyPassword=your-key-password
```

Then build:

```bash
./gradlew :manager:assembleRelease
```

The APK is written to `manager/build/outputs/apk/release/` as `fOxygen_v1.2_<versionCode>-release_<timestamp>.apk`. Use `:manager:assembleDebug` for a debug build.

> Release builds strip `android.util.Log` calls through R8 rules. Use a debug build when you need to read the app or server logs.

### Build with GitHub Actions

You can build entirely on GitHub, even from a phone, with the workflow in `.github/workflows/build.yml`.

1. Open the **Actions** tab of your fork and enable workflows.
2. Optional but recommended: store a permanent signing key as repository secrets (one time):

   ```bash
   keytool -genkeypair -keystore foxygen.jks -storetype PKCS12 -alias foxygen -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 foxygen.jks | gh secret set KEYSTORE_B64
   gh secret set KEYSTORE_PASSWORD
   gh secret set KEY_ALIAS
   gh secret set KEY_PASSWORD
   ```

   For a PKCS12 keystore, `KEY_PASSWORD` must equal `KEYSTORE_PASSWORD`. Keep a private backup of the keystore: if you lose it, new builds can no longer update installed ones.
3. Run the workflow:

   ```bash
   gh workflow run build.yml -f build_type=Release
   gh run watch
   ```

4. Download the APK (or, after a failed build, the log):

   ```bash
   gh run list --limit 3
   gh run download <run-id> -n fOxygen-apk
   gh run download <run-id> -n fOxygen-build-log
   ```

If the signing secrets are missing or invalid, the workflow generates a temporary random key and prints a warning. Android requires every APK to be signed, but an APK signed with a random key cannot update an existing install, so uninstall the old build first.

### Publish a release

```bash
gh workflow run build.yml -f build_type=Release -f publish=true -f tag=v1.2
```

Publishing requires valid signing secrets (the workflow refuses to publish an APK signed with a random key). It attaches the APK and its SHA-256 checksum to a GitHub release.

### Install

```bash
adb install -r manager/build/outputs/apk/release/fOxygen_*.apk
```

Or copy the APK to your phone and open it. fOxygen uses a different application id than AxM-Next, so it installs next to it as a separate app, with its own permissions. Stop the old server and uninstall AxM-Next before activating fOxygen.

## 📖 Roadmap
- [x] Wireless Debugging activator.
- [x] Command-line / Root activator.
- [x] Shell executor basic support (ADB / non-root).
- [x] Auto-activate when using Wireless Debugging (test).
- [x] [Plugin](https://fahrez182.github.io/AxManager/plugin/what-is-plugin.html) system for third-party extensions.
- [x] Developer mode and advanced debugging tools.
- [x] Server auto-recovery and job-based recovery (no wake lock).
- [x] axsh terminal shell with Rootless debugging.
- [ ] App optimization based on profiles.

## 🤝 Contribution
Contributions are welcome. Open an **issue**, submit a **pull request**, or start a discussion for ideas and improvements.

## 🙏 Credits
- **[AxManager](https://github.com/fahrez182/AxManager)** by **fahrez182**: the original project this fork is based on.
- **[Axora](https://github.com/corvexis/Axora)**: UI/UX inspiration.
- **[FolkPure](https://github.com/matsuzaka-yuki/FolkPure)**: UI/UX inspiration.
- **[Magisk](https://github.com/topjohnwu/Magisk)**: BusyBox and plugin (unrooted module) ideas.
- **[Shizuku](https://github.com/RikkaApps/Shizuku) / [Shizuku-API](https://github.com/RikkaApps/Shizuku-API)**: starting point and reference for Android IPC and ADB-based permission handling.
- **[KernelSU](https://github.com/tiann/KernelSU) / [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next)**: inspiration for the UI and WebUI features.

## ⚠️ Notices & legal disclaimer
This project includes adapted portions of code from:
- Shizuku Manager (© Rikka Apps), licensed under the Apache License 2.0. Repository: <https://github.com/RikkaApps/Shizuku>
- AxManager (© fahrez182), licensed under the Apache License 2.0. Repository: <https://github.com/fahrez182/AxManager>
- Other open-source projects as credited above.

fOxygen does not include or distribute any original Shizuku Manager visual assets, and it is not an official replacement for any of the projects above. Axora and FolkPure are credited as design inspiration only. All adapted code is used with attribution and in compliance with the Apache License 2.0.

## 📜 License
Licensed under the [Apache License 2.0](LICENSE). Modifications are made by the fOxygen maintainer as described above.
