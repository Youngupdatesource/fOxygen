package frb.axeron.manager.ui.util

import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.shell.Axsh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells when a QuickShell command left something running behind it (`cmd &`, `nohup ...`) and
 * when that something ends, reporting each change through [onEvent] as `[bgtask] ...`.
 *
 * No process list is read. Every command runs under `setsid` (see QuickShellSession), so its
 * pid is also its process group id and session id, and everything it started stays in that
 * group. `kill -0 -- -pgid` succeeds exactly while some member of the group is alive: one exit
 * status answers "is anything still running?", with no `ps` flags or /proc parsing to get
 * wrong. (`ps -o NAME` is not accepted by every toybox build; its error went to /dev/null and
 * the list came back empty, so nothing was ever reported.)
 *
 * The check runs through the Axeron server, as the server is the same uid as the command.
 * Jobs that start a session of their own (a daemon calling setsid) leave the group and are not
 * followed.
 */
class BgTaskTracker(
    private val scope: CoroutineScope,
    private val onEvent: (String) -> Unit,
) {
    /** Command group id -> what was reported for it (the shell's `$!` when it is known). */
    private val tracked = ConcurrentHashMap<Int, String>()

    private var watchJob: Job? = null

    /** A command started. Nothing to remember: the group is checked once it has ended. */
    @Suppress("UNUSED_PARAMETER")
    fun onStarted(pid: Int) = Unit

    /**
     * The command's own shell ended: if its group still has live members, they went background.
     * [hint] are pids the session already knows were started (the shell's `$!`); they only name
     * the task, liveness always comes from the group.
     */
    fun onFinished(pid: Int, hint: IntArray = IntArray(0)) {
        scope.launch(Dispatchers.IO) {
            delay(SETTLE_MS)
            val alive = groupAlive(pid)
            if (DEBUG_BG) onEvent("[dbg] onFinished($pid): hint=${hint.toList()} groupAlive=$alive")
            if (alive != true) return@launch
            val label = if (hint.isNotEmpty()) hint.joinToString(",") else "session=$pid"
            if (tracked.putIfAbsent(pid, label) == null) {
                onEvent(if (hint.isNotEmpty()) "[bgtask] pid=$label" else "[bgtask] $label")
                watch()
            }
        }
    }

    /** The command's background jobs were stopped on purpose: nothing to report. */
    fun onStopped() = Unit

    /** Tells when each background group is gone. Runs only while something is tracked. */
    private fun watch() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch(Dispatchers.IO) {
            while (isActive && tracked.isNotEmpty()) {
                delay(WATCH_MS)
                for ((group, label) in tracked.entries.toList()) {
                    // null = could not ask (server gone): keep waiting, do not claim it exited.
                    if (groupAlive(group) == false) {
                        tracked.remove(group)
                        onEvent(
                            if (label.startsWith("session=")) "[bgtask] exited $label"
                            else "[bgtask] exited pid=$label"
                        )
                    }
                }
            }
        }
    }

    fun close() {
        watchJob?.cancel()
        tracked.clear()
    }

    /**
     * True while the process group [pgid] has a live member, false once it is empty, null when
     * the server could not be asked. The two spellings cover shells that read `-0 --` as an
     * option error and ones that do not know `-s 0`; either one succeeding means alive.
     */
    private fun groupAlive(pgid: Int): Boolean? {
        // Never 0, 1 or -1: that would be this uid's every process.
        if (pgid <= 1) return false
        if (!Axeron.pingBinder()) return null
        return try {
            val check = Axsh.exec(
                arrayOf(
                    "/system/bin/sh", "-c",
                    "kill -s 0 -- -$pgid 2>/dev/null || kill -0 -- -$pgid 2>/dev/null"
                ),
                null,
                null
            )
            runCatching { check.outputStream.close() }
            val code = check.waitFor()
            runCatching { check.inputStream.close() }
            runCatching { check.errorStream.close() }
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "group check failed", e)
            if (DEBUG_BG) onEvent("[dbg] group check failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "BgTaskTracker"
        const val SETTLE_MS = 300L
        const val WATCH_MS = 2000L

        /** Temporary: release builds strip every Log call, so facts go to the terminal. */
        const val DEBUG_BG = false
    }
}
