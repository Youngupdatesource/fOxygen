package frb.axeron.manager.ui.util

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.shell.Axsh
import frb.axeron.server.Environment
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one QuickShell command at a time, like a terminal does.
 *
 * A command is done when its shell exits, even if it left jobs running in the background
 * (`cmd &`). Those jobs are then either stopped (background processing restricted) or left
 * running and reported through [ProcessListener.onProcessBackground].
 *
 * How it knows what was left behind:
 *  - The command runs under `setsid`, so it leads its own session: its pid is also its process
 *    group id, and `kill -- -pid` reaches the command and everything it started, never the server.
 *  - An EXIT trap in the command's shell writes `$!` (the last background job) to stderr. This
 *    works even when the job has redirected its output (`cmd >/dev/null 2>&1 &`) and so holds no
 *    pipe open, which is the case where watching the pipes alone sees nothing.
 *  - Output pipes still open after the shell is gone also mean a job is running.
 *
 * This replaces the API's AxeronCommandSession for QuickShell only: that class's behaviour
 * comes from whichever API version is pinned, this one does not depend on it.
 */
class QuickShellSession(private val application: Application) {

    interface ResultListener {
        fun output(output: CharSequence?)
        fun onError(error: CharSequence?)
    }

    interface ProcessListener {
        fun onProcessCreated(pid: Int, command: String)
        fun onProcessRunning(input: String)
        fun onProcessFinished(exitCode: Int, lastOutput: String)

        /** The command's shell is done but these processes (pids may be stale) were left behind. */
        fun onProcessBackground(pids: IntArray) {}

        /** Background processing is restricted: what the command left running was stopped. */
        fun onBackgroundStopped() {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var resultListener: ResultListener? = null

    @Volatile
    private var processListener: ProcessListener? = null

    @Volatile
    private var restrictBackground = true

    /** The latest command; earlier ones may still have background jobs reading out. */
    private var current: Run? = null

    fun setResultListener(listener: ResultListener?) {
        resultListener = listener
    }

    fun setProcessListener(listener: ProcessListener?) {
        processListener = listener
    }

    /**
     * True: what a command leaves running in the background is stopped when its shell exits.
     * False: it keeps running and is reported.
     */
    fun setRestrictBackground(restrict: Boolean) {
        restrictBackground = restrict
    }

    /** Starts [input] as a new command, or writes it to the running one's stdin. */
    @Synchronized
    fun runCommand(input: String, compatMode: Boolean) {
        val run = current
        if (run != null && run.isRunning) {
            try {
                run.write(input)
            } catch (e: IOException) {
                postError("command: ${e.message}")
            }
        } else {
            startNewProcess(input, compatMode)
        }
    }

    /** Stops the running command: TERM to its whole session, background jobs included. */
    @Synchronized
    fun killSession() {
        val run = current
        if (run != null && run.isRunning) killGroup(run.pid)
    }

    fun destroy() = killSession()

    // ---------------------------------------------------------------------------------------
    // Process start
    // ---------------------------------------------------------------------------------------

    private fun startNewProcess(command: String, compatMode: Boolean) {
        val process = try {
            Axsh.exec(sessionCmd(command, compatMode), serverEnv()?.env, null)
        } catch (e: Throwable) {
            postError("Failed to start process: ${e.message}")
            return
        }
        current = Run(
            command,
            process.outputStream,
            process.inputStream,
            process.errorStream
        ) { process.waitFor() }.also { it.start() }
    }

    /**
     * The one place that reads the server's environment (AXERONDIR, PATH with the plugin xbin,
     * user env). Direct call on purpose: the manager is minified, reflection would break.
     */
    private fun serverEnv(): Environment? = try {
        Axeron.getEnvironment()
    } catch (e: Throwable) {
        Log.w(TAG, "no server env: ${e.message}")
        null
    }

    /**
     * `setsid` + the wrapper that echoes the shell's pid to stderr, then the command behind an
     * EXIT trap. The trap goes before the command on the same line, so line numbers in the
     * shell's errors stay the user's. Compat mode takes busybox's setsid instead of the system's.
     */
    private fun sessionCmd(cmd: String, compatMode: Boolean): Array<String> {
        val traced = "trap 'echo \"$BG_MARK\$!\" 1>&2' EXIT; $cmd"
        val wrapper = "export PARENT_PID=\$\$; " +
                "echo \$PARENT_PID 1>&2; " +
                "exec -a \"QuickShell\" /system/bin/sh -c \"\$0\""
        return arrayOf(*setsidCommand(compatMode), "/system/bin/sh", "-c", wrapper, traced)
    }

    private fun setsidCommand(compatMode: Boolean): Array<String> {
        val busybox = "${application.applicationInfo.nativeLibraryDir}/libbusybox.so"
        if (compatMode) return arrayOf(busybox, "setsid")
        val system = systemApplet("setsid")
        return if (system != null) arrayOf(system) else arrayOf(busybox, "setsid")
    }

    /** A real system applet, not a busybox symlink: same rule as the plugin igniter's. */
    private fun systemApplet(name: String): String? =
        arrayOf("/system/bin", "/system/xbin", "/vendor/bin")
            .asSequence()
            .map { File(it, name) }
            .firstOrNull { it.canExecute() && !isBusybox(it) }
            ?.absolutePath

    private fun isBusybox(file: File): Boolean = try {
        file.canonicalFile.name.startsWith("busybox")
    } catch (_: Exception) {
        false
    }

    /** TERM to the process group [pgid], which is a command's own session (see class doc). */
    private fun killGroup(pgid: Int) {
        Thread({ killGroupNow(pgid) }, "QuickShellKill").start()
    }

    /**
     * Same as [killGroup], but waits and tells whether anything was still alive in the group:
     * `kill` fails with "No such process" when the group is empty, so success means a job
     * had been left behind. Must not run on the main thread.
     */
    private fun killGroupNow(pgid: Int): Boolean {
        // Never 0, 1 or -1: that would reach this uid's every process.
        if (pgid <= 1) return false
        return try {
            val kill = Axsh.exec(
                arrayOf("/system/bin/sh", "-c", "kill -s TERM -- -$pgid"),
                null,
                null
            )
            runCatching { kill.outputStream.close() }
            val code = kill.waitFor()
            runCatching { kill.inputStream.close() }
            runCatching { kill.errorStream.close() }
            code == 0
        } catch (e: Throwable) {
            Log.w(TAG, "kill $pgid: ${e.message}")
            false
        }
    }

    private fun postError(error: String) {
        val listener = resultListener ?: return
        mainHandler.post { listener.onError(error) }
    }

    private fun postProcess(block: ProcessListener.() -> Unit) {
        val listener = processListener ?: return
        mainHandler.post { listener.block() }
    }

    private fun close(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: IOException) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // One command
    // ---------------------------------------------------------------------------------------

    /**
     * One command: its shell and its output. The readers live as long as anything holds the
     * output open, background jobs included, so a later command never cuts them off.
     */
    private inner class Run(
        val command: String,
        stdin: OutputStream,
        stdoutStream: InputStream,
        stderrStream: InputStream,
        private val waitForShell: () -> Int,
    ) {
        private val writer = BufferedWriter(OutputStreamWriter(stdin))
        private val stdout = BufferedReader(InputStreamReader(stdoutStream))
        private val stderr = BufferedReader(InputStreamReader(stderrStream))

        /** Guarded by this Run: stdout read before stderr delivered the pid. */
        private val early = StringBuilder()

        /** Guarded by this Run: stderr read while the pid line has not arrived yet. */
        private val pending = StringBuilder()
        private val lastOutput = AtomicReference("")

        /** Counted down by the EXIT trap's line, or stderr's end when there is none. */
        private val bgMarkSeen = CountDownLatch(1)

        @Volatile
        var pid = -1

        @Volatile
        private var backgroundPid = -1

        /** The pid line was seen and onProcessCreated posted. Guarded by this Run. */
        private var started = false

        /** The shell exited: no more input, finished reported. */
        @Volatile
        private var shellDone = false

        val isRunning: Boolean get() = !shellDone

        @Throws(IOException::class)
        fun write(input: String) {
            processListener?.onProcessRunning(input)
            writer.write(input)
            writer.newLine()
            writer.flush()
        }

        fun start() {
            val out = Thread({ readStdout() }, "QuickShellOut")
            val err = Thread({ readStderr() }, "QuickShellErr")
            out.start()
            err.start()
            Thread({ awaitShell(out, err) }, "QuickShellWait").start()
        }

        private fun readStdout() {
            try {
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = stdout.read(buffer)
                    if (read == -1) break
                    val part = String(buffer, 0, read)
                    synchronized(this) {
                        // Held until onProcessCreated, so it isn't lost and lands after it.
                        if (!started) early.append(part) else output(part)
                    }
                }
            } catch (e: IOException) {
                postError("stdout: ${e.message}")
            } finally {
                close(stdout)
            }
        }

        private fun readStderr() {
            try {
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = stderr.read(buffer)
                    if (read == -1) break
                    var part = String(buffer, 0, read)

                    synchronized(this) {
                        if (!started) {
                            // The wrapper echoes its pid to stderr first: cut exactly that line,
                            // keep everything else byte for byte. Until it arrives, stderr is
                            // kept (a pid line may be split across reads, or never come).
                            val text = pending.toString() + part
                            pending.setLength(0)
                            val m = PID_LINE.find(text)
                            if (m == null) {
                                pending.append(text)
                                part = ""
                            } else {
                                pid = m.groupValues[1].toInt()
                                part = text.substring(0, m.range.first) +
                                        text.substring(m.range.last + 1)
                                started = true
                                val p = pid
                                val c = command
                                postProcess { onProcessCreated(p, c) }
                                if (early.isNotEmpty()) {
                                    output(early.toString())
                                    early.setLength(0)
                                }
                            }
                        }
                    }

                    val bg = BG_LINE.find(part)
                    if (bg != null) {
                        bg.groupValues[1].toIntOrNull()?.let { backgroundPid = it }
                        part = part.substring(0, bg.range.first) + part.substring(bg.range.last + 1)
                        bgMarkSeen.countDown()
                    }
                    if (part.isNotEmpty()) postError(part)
                }
            } catch (e: IOException) {
                postError("stderr: ${e.message}")
            } finally {
                bgMarkSeen.countDown()
                // No pid line ever came (the launcher failed): show what it said.
                synchronized(this) { flushPending() }
                close(stderr)
            }
        }

        /** Joins [thread] until [endNanos] (System.nanoTime); join(0) would wait forever. */
        private fun joinUntil(thread: Thread, endNanos: Long) {
            val ms = TimeUnit.NANOSECONDS.toMillis(endNanos - System.nanoTime())
            if (ms > 0) thread.join(ms)
        }

        /** Guarded by this Run. */
        private fun flushPending() {
            if (pending.isEmpty()) return
            val text = pending.toString()
            pending.setLength(0)
            postError(text)
        }

        private fun awaitShell(out: Thread, err: Thread) {
            var code = -1
            try {
                // The command's shell: its exit code is the command's.
                code = waitForShell()
                bgMarkSeen.await(BG_MARK_WAIT_MS, TimeUnit.MILLISECONDS)
                // Both readers get the same short deadline. A reader is still alive for a few
                // microseconds after its pipe closed; judged before that, an ordinary command
                // would look like it left a job behind.
                val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BG_MARK_WAIT_MS)
                joinUntil(out, end)
                joinUntil(err, end)
            } catch (e: Throwable) {
                // A dead binder must still end the command, or the UI waits on it forever.
                Log.w(TAG, "waiting for the shell: ${e.message}")
            }
            Log.d(TAG, "shell done, exitCode = $code")

            shellDone = true
            close(writer)

            // Output still held open with the shell gone means a job is running; the EXIT trap's
            // `$!` also names one that redirected its output and holds nothing open.
            val holdsOutput = out.isAlive || err.isAlive
            val bg = backgroundPid
            if (DEBUG_BG) postError("[dbg] shell gone: pid=$pid holdsOutput=$holdsOutput bg=$bg restrict=$restrictBackground\n")
            if (holdsOutput || bg > 0) {
                if (restrictBackground) {
                    // Said only when something was surely alive: open output, or a group the
                    // kill actually reached (a `$!` alone may be long gone).
                    val killed = killGroupNow(pid)
                    if (holdsOutput || killed) postProcess { onBackgroundStopped() }
                } else if (bg > 0) {
                    postProcess { onProcessBackground(intArrayOf(bg)) }
                }
            }

            var wasStarted = false
            synchronized(this) {
                wasStarted = started
                // Never got a pid line: still show what it printed, and whatever comes later.
                started = true
                if (early.isNotEmpty()) {
                    output(early.toString())
                    early.setLength(0)
                }
                flushPending()
            }
            if (wasStarted) {
                val exit = code
                val last = lastOutput.get()
                postProcess { onProcessFinished(exit, last) }
            }
        }

        private fun output(part: String) {
            val listener = resultListener ?: return
            lastOutput.set(part)
            mainHandler.post { listener.output(part) }
        }
    }

    private companion object {
        const val TAG = "QuickShellSession"

        /**
         * Temporary: prints background-detection facts into the terminal. Release builds strip
         * every android.util.Log call (server/consumer-rules.pro), so logcat shows nothing.
         */
        const val DEBUG_BG = false

        /** Written to stderr by the command shell's EXIT trap, followed by its `$!`. */
        const val BG_MARK = "__axeron_bg__="

        /** How long to wait for the EXIT trap's line once the shell is gone. */
        const val BG_MARK_WAIT_MS = 500L

        val PID_LINE = Regex("(?m)^[ \\t]*(\\d+)[ \\t]*(?:\\r?\\n|\$)")
        val BG_LINE = Regex("(?m)^" + BG_MARK + "(\\d*)[ \\t]*(?:\\r?\\n|\$)")
    }
}
