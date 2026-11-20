## fOxygen v1.4

fOxygen (formerly AxM-Next) is a modified fork of [AxManager](https://github.com/fahrez182/AxManager) by fahrez182.

### What's new (v1.3 -> v1.4)
- **Axeron-API v3.0.0 (`30000`) Migration**: Switched the `api` submodule to `Youngupdatesource/Axeron-API` (`30000`), aligned `versionCode` to follow `api_version_code` (`30000`), removed legacy `:rish`, `:shell`, `:runtime`, `:axerish`, and root `axsh/` modules in favor of `api/axsh`, and migrated `Axeron.newProcess` calls to `Axsh.exec`.
- **Direct AIDL v3 Server & Rootless Support**: Updated `AxeronService`, `ClientCapability`, `AxshServer`, and `ShellBinderRequestHandler` to implement the official API v3 AIDL methods (`newAxshProcess`, `openAxshChannel`, `getAxshSocketName`, `getClientCapability`, `getFlagsForUids`, `setPreferSystemBinaries`, no-op `setWakeLock`/`isWakeLockHeld`), added `BIND_APPLICATION_CLIENT_UID` and `ServerInfo` manager verification, and preserved per-app rootless debugging (`libaxdrop.so` / `RootlessConstants`).
- **Release Tag Update Checker**: Updated GitHub release detection (`Downloader.kt` & `Home.kt`) to compare the latest release `tag_name` semantically against `BuildConfig.VERSION_NAME` (e.g., `v1.4`), removing the filename `versionCode` regex dependency.
- **Privilege Manager Terminal Launch Fix**: Fixed `TerminalAppDialog` (`libtermux.so`) Launch cancellation in `Privilege.kt` by hoisting the coroutine scope outside the dialog condition, added parent directory creation in `AxshLauncher.ensure`, and auto-refreshed `/sdcard/axeron/axsh` on binder connect.
- **Legacy Daemon (`14800`) Upgrade & Settings Editor Fixes**: Prevented `IOException: AxManager server is too old for axsh` crashes in `ServerRestart.kt` by falling back to `IShizukuService.newProcess` and `Axeron.destroy()` + `ServerGuard.restart`, and fixed `resetprop` execution in `SettingsRepository.kt` using `Axsh.exec`.

### Install
Download the APK below and verify it against the attached `.sha256` file. If Android refuses to install over an existing fOxygen build, uninstall the old one first, because it was signed with a different key.

See the [README](https://github.com/Youngupdatesource/fOxygen#readme) for details and credits.
