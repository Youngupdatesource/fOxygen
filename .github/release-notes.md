## fOxygen v1.2

fOxygen (formerly AxM-Next) is a modified fork of [AxManager](https://github.com/fahrez182/AxManager) by fahrez182.

### What's new
- **Per-UID Binder Isolation & Certificate Binding**: Adopted `ClientCapability` proxy isolation and SHA-256 signing certificate verification (`AxeronConfig` v3), ensuring third-party client apps only receive UID-scoped capabilities and automatically lose access if revoked or re-signed.
- **Direct Abstract Socket (`AxshServer`) & Native `--axsh-daemon`**: Integrated `AxshServer` (`@axeron.<hex>` with `SO_PEERCRED` verification) and `openAxshChannel` (`SCM_RIGHTS`) alongside the native C++ `--axsh-daemon`, eliminating `app_process` JVM startup and Binder round-trip latency for `axsh` sessions while preserving per-app rootless debugging (`libaxdrop.so`).
- **Offline Settings Editor (`SettingsAccess`)**: Automatically grants `WRITE_SETTINGS` (via `appops`) alongside `WRITE_SECURE_SETTINGS` when the manager binds, and falls back to `ContentResolver` so Global, Secure, and System tables can be read and edited even when `axeron_server` is stopped.
- **Pinned Developer Settings & Mutex-Guarded Activation**: Added `DeveloperSettings` candidate intents pinned to `com.android.settings` (`:settings:fragment_args_key = toggle_adb_wireless`) and `starterMutex` synchronization in `AdbStarter`.
- **Startup Entrance Animation Bugfix**: Fixed the startup `Entrance` animation so it plays strictly once per app process launch and never re-triggers when tapping or scrubbing the bottom navigation capsule.
- **Developer-Gated Plugin Logs & Startup Cleanup**: Removed `DaemonGuard` (saving 6 `getprop` process forks on every server start), gated plugin script file logging (`$AXERONDIR/logs/<id>.post-fs-data.log` and `.uninstall.log`) behind the in-app **Enable Developer Mode** switch (redirecting to `/dev/null` when disabled), stripped non-essential Logcat/Logger calls in Release builds via R8 and C++ macros, and removed the 500-line in-memory `ActivationLog` buffer.

### Install
Download the APK below and verify it against the attached `.sha256` file. Because the application id changed, fOxygen installs as a separate app and does not update AxM-Next in place. If Android refuses to install over an existing fOxygen build, uninstall the old one first, because it was signed with a different key.

See the [README](https://github.com/Youngupdatesource/fOxygen#readme) for details and credits.
