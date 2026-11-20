package frb.axeron.manager.ui.util

import frb.axeron.server.RootlessConstants
import java.io.File

/**
 * "Rootless debugging": lowers a QuickShell command from root to the shell user (uid 2000), so
 * it behaves like `adb shell` while the Axeron server itself keeps running as root.
 *
 * Why not plain `setpriv`: on rooted phones `setpriv` is usually BusyBox's (or toybox's), and
 * both only handle capabilities, they cannot change uid. So the work is done by `libaxdrop.so`,
 * a tiny native helper shipped with the app (one exec, nothing left running behind):
 *  - capability bounding set emptied, then shell uid/gid and supplementary groups;
 *  - SELinux domain u:r:shell:s0 for the exec, dropped again if the policy refuses it;
 *  - ambient caps cleared.
 * A util-linux style `setpriv` (one that accepts --reuid) is only used as a fallback.
 *
 * If neither works the command is NOT run: silently staying root would defeat the point.
 */
object RootlessShell {

    private const val SHELL_UID = 2000

    // adbd's supplementary groups for the shell user: shell, log, adb, input, inet, sdcard_*,
    // mount, net_*, readproc, bluetooth, usb, ext_data_rw, ext_obb_rw, readtracefs, graphics.
    private const val SHELL_GROUPS = RootlessConstants.SHELL_GROUPS

    private const val SHELL_CONTEXT = RootlessConstants.SHELL_CONTEXT
    private const val SHELL = "/system/bin/sh"

    /** Where the app's extracted native executables live (same place libaxeron.so runs from). */
    fun helperPath(nativeLibraryDir: String): String = File(nativeLibraryDir, "libaxdrop.so").path

    /** Wraps [command] so it runs as the shell user. Output and stdin stay attached (exec). */
    fun wrap(command: String, helper: String): String {
        val quoted = shQuote(command)
        return buildString {
            append("D=${shQuote(helper)}; ")
            // 1) our helper
            append("if [ -x \"\$D\" ]; then exec \"\$D\" -c $SHELL_CONTEXT -G $SHELL_GROUPS -- $SHELL -c $quoted; fi; ")
            // 2) a setpriv that can really change uid (util-linux style); BusyBox's cannot
            append("if setpriv --reuid=$SHELL_UID --regid=$SHELL_UID --clear-groups $SHELL -c : >/dev/null 2>&1; then ")
            append("exec setpriv --reuid=$SHELL_UID --regid=$SHELL_UID --groups=$SHELL_GROUPS ")
            append("--bounding-set=-all --inh-caps=-all $SHELL -c $quoted; fi; ")
            append("echo 'rootless: no way to drop to the shell user (libaxdrop.so missing), command not run' >&2; exit 127")
        }
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
