## fOxygen v1.3

fOxygen (formerly AxM-Next) is a modified fork of [AxManager](https://github.com/fahrez182/AxManager) by fahrez182.

### What's new (v1.2 -> v1.3)
- **Version & Release Bump**: Updated app version to **v1.3** (`versionCode` **15500**, up from `14800`), updated the GitHub Actions release tag default to `v1.3`, and aligned build documentation.
- **Per-UID Binder Isolation & Certificate Binding**: Adopted `ClientCapability` proxy isolation and SHA-256 signing certificate verification (`AxeronConfig` v3), ensuring third-party client apps only receive UID-scoped capabilities and automatically lose access if revoked or re-signed.
- **Direct Abstract Socket (`AxshServer`) & Native `--axsh-daemon`**: Integrated `AxshServer` (`@axeron.<hex>` with `SO_PEERCRED` verification) and `openAxshChannel` (`SCM_RIGHTS`) alongside the native C++ `--axsh-daemon`, eliminating `app_process` JVM startup and Binder round-trip latency for `axsh` sessions while preserving per-app rootless debugging (`libaxdrop.so`).
- **Native C++ Igniter (`libaxeron.so`)**: Ported the plugin igniter and `--stop-all` watchdog into native C++ (`igniter.cpp`), keeping `Igniter.kt` (`ax_reignite.dex`) as fallback and preserving debug symbols for `libaxreignite.so`.
- **WakeLock-Free Event-Driven Recovery**: Removed `WakeLockController`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and Doze exemptions; added `ServerWatch` and `ServerRecoveryWorker` (one-shot WorkManager recovery with exponential backoff on binder death).
- **Offline Settings Editor (`SettingsAccess`) & ADB Helpers**: Automatically grants `WRITE_SETTINGS` (via `appops`) alongside `WRITE_SECURE_SETTINGS` when the manager binds, falls back to `ContentResolver` when `axeron_server` is stopped, pins `DeveloperSettings` intents (`toggle_adb_wireless`), and adds `starterMutex` synchronization in `AdbStarter`.
- **Splash Screen, Navigation & UI Polish**: Added `androidx.core:core-splashscreen` (`Theme.AxManager.Starting`) and `androidx.profileinstaller`, fixed `Entrance` (`StartupMotion`) so it plays once per launch, added `SafeNavigation` to prevent duplicate screen pushes, and improved `TerminalOutput` search/select-all, `BannerTone`, and translations.
- **Developer-Gated Plugin Logs & Startup Cleanup**: Removed `DaemonGuard` (saving 6 `getprop` process forks on server start), gated plugin script file logging (`$AXERONDIR/logs/<id>.post-fs-data.log` and `.uninstall.log`) behind the in-app **Enable Developer Mode** switch (redirecting to `/dev/null` when disabled), stripped non-essential Release logs via R8 and C++ macros, and removed the 500-line in-memory `ActivationLog` buffer.

### Install
Download the APK below and verify it against the attached `.sha256` file. Because the application id changed, fOxygen installs as a separate app and does not update AxM-Next in place. If Android refuses to install over an existing fOxygen build, uninstall the old one first, because it was signed with a different key.

See the [README](https://github.com/Youngupdatesource/fOxygen#readme) for details and credits.
