package frb.axeron.manager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import frb.axeron.api.Axeron
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.utils.AnsiFilter
import frb.axeron.manager.R
import frb.axeron.manager.ui.util.BgTaskTracker
import frb.axeron.manager.ui.util.QuickShellSession
import frb.axeron.manager.ui.util.RootlessShell
import frb.axeron.manager.ui.util.TerminalBuffer
import frb.axeron.manager.ui.util.TerminalPrefs
import java.io.File

class QuickShellViewModel(application: Application) : AndroidViewModel(application) {

    var session: QuickShellSession = QuickShellSession(application)
    private var savedCommand: TextFieldValue? = null

    // Lives here, not in the screen, so the log survives leaving and re-entering QuickShell.
    val terminal = TerminalBuffer<OutputType>(
        spoolDir = File(application.cacheDir, "terminal"),
        unlimited = TerminalPrefs.unlimited
    )

    /** Reports what a command leaves running in the background as `[bgtask] pid=...`. */
    private val bgTasks = BgTaskTracker(viewModelScope) { append(OutputType.TYPE_START, it) }

    /** What the user typed for the command that is running; the wrapped form is never shown. */
    private var displayCommand: String? = null
    private var wrappedCommand: String? = null
    private var currentPid = -1

    /** What the session reported for the command that just ended; read by onProcessFinished. */
    private var backgroundHint = IntArray(0)
    private var backgroundStopped = false

    init {
        session.setProcessListener(object : QuickShellSession.ProcessListener {
            override fun onProcessCreated(pid: Int, rawCommand: String) {
                Log.i("QuickShellViewModel", "onProcessCreated: $pid")
                val command = if (wrappedCommand?.trim() == rawCommand.trim()) displayCommand ?: rawCommand else rawCommand
                currentPid = pid
                bgTasks.onStarted(pid)
                if (command.lines().size > 1) {
                    append(OutputType.TYPE_COMMAND, "[command]")
                    append(OutputType.TYPE_COMMAND, command.trim())
                } else append(OutputType.TYPE_COMMAND, "[command] ${command.trim()}")
                append(OutputType.TYPE_START, "[start] pid=$pid")
                // `clear`/`top` redraws wipe this command's output only, not the session log.
                terminal.markScreenStart()
                savedCommand = TextFieldValue(text = command, selection = TextRange(command.length))
                commandText = TextFieldValue("") // clear input
                execMode = "Inputs"
                isRunning = true
            }

            override fun onProcessRunning(input: String) {
                Log.i("QuickShellViewModel", "onProcessRunning: $input")
                val tagInput = if (input.lines().size > 1) "[input]\n" else "[input] "
                append(OutputType.TYPE_STDIN, tagInput + input.trim())
                commandText = TextFieldValue("")
            }

            override fun onProcessBackground(pids: IntArray) {
                backgroundHint = pids
            }

            override fun onBackgroundStopped() {
                backgroundStopped = true
                append(OutputType.TYPE_START, "[bgtask] stopped by shell restriction")
            }

            override fun onProcessFinished(exitCode: Int, lastOutput: String) {
                Log.i("QuickShellViewModel", "onProcessFinished: $exitCode")
                if (currentPid > 0) {
                    // Stopped by the restriction: nothing is left to report or to follow.
                    if (backgroundStopped) bgTasks.onStopped()
                    else bgTasks.onFinished(currentPid, backgroundHint)
                }
                backgroundHint = IntArray(0)
                backgroundStopped = false
                currentPid = -1
                terminal.endStreams()
                if (!AnsiFilter.isScreenControl(lastOutput)) {
                    append(OutputType.TYPE_EXIT, "[exit] code=$exitCode")
                    append(OutputType.TYPE_SPACE, "")
                }
                execMode = "Commands"
                if (savedCommand != null) {
                    commandText = savedCommand!!
                    savedCommand = null
                }
                isRunning = false
            }
        })

        session.setResultListener(object : QuickShellSession.ResultListener {
            override fun output(output: CharSequence?) {
                output?.let { terminal.write(OutputType.TYPE_STDOUT, it.toString()) }
            }

            override fun onError(error: CharSequence?) {
                error?.let { terminal.write(OutputType.TYPE_STDERR, it.toString()) }
            }

        })
    }

    private val prefs = AxeronSettings.getPreferences()

    enum class OutputType(val labelId: Int) {
        TYPE_COMMAND(R.string.type_command),
        TYPE_START(R.string.type_start),
        TYPE_STDIN(R.string.type_stdin),
        TYPE_STDOUT(R.string.type_stdout),
        TYPE_STDERR(R.string.type_stderr),
        TYPE_THROW(R.string.type_throw),
        TYPE_SPACE(R.string.type_space),
        TYPE_EXIT(R.string.type_exit)
    }

    enum class KeyEventType(val labelId: Int) {
        VOLUME_UP(R.string.volume_up),
        VOLUME_DOWN(R.string.volume_down)
    }


    var isShellRestrictionEnabled: Boolean by mutableStateOf(
        prefs.getBoolean("shell_restriction", true)
    )
        private set

    // After the property above: an init block earlier in the class would read it uninitialised.
    init {
        session.setRestrictBackground(isShellRestrictionEnabled)
    }

    fun setShellRestriction(enable: Boolean) {
        isShellRestrictionEnabled = enable
        session.setRestrictBackground(enable)
        prefs.edit {
            putBoolean("shell_restriction", enable)
        }
    }

    var isCompatModeEnabled: Boolean by mutableStateOf(
        prefs.getBoolean("shell_compat_mode", true)
    )
        private set

    fun setCompatMode(enable: Boolean) {
        isCompatModeEnabled = enable
        prefs.edit {
            putBoolean("shell_compat_mode", enable)
        }
    }


    /** Rootless debugging: only meaningful (and only offered) when the server runs as root. */
    var isRootlessEnabled: Boolean by mutableStateOf(prefs.getBoolean("shell_rootless", false))
        private set

    fun setRootless(enable: Boolean) {
        isRootlessEnabled = enable
        prefs.edit { putBoolean("shell_rootless", enable) }
    }

    /** True when the server is root, so lowering to shell is possible. */
    val isRootMode: Boolean
        get() = runCatching { Axeron.pingBinder() && Axeron.getAxeronInfo().isRoot() }.getOrDefault(false)

    var isRunning by mutableStateOf(false)
        private set

    var commandText by mutableStateOf(TextFieldValue(""))
        private set

    var execMode by mutableStateOf("Commands")
        private set

    fun setCommand(text: TextFieldValue) {
        commandText = text
    }

    fun clear() {
        terminal.clear()
    }

    fun stop() {
        session.killSession()
    }

    fun runShell() {
        val cmd = commandText.text.ifBlank { return }
            .replace(Regex("(?i)adb\\s+shell\\s+"), "")
            .replace(Regex("[^\\p{Print}\\n]"), "") // sanitize

        // While a process runs, the input is its stdin: never wrap that.
        val lowered = !isRunning && isRootlessEnabled && isRootMode
        val toRun = if (lowered) {
            RootlessShell.wrap(
                cmd,
                RootlessShell.helperPath(getApplication<Application>().applicationInfo.nativeLibraryDir)
            )
        } else cmd
        if (lowered) {
            displayCommand = cmd
            wrappedCommand = toRun
        }
        session.runCommand(toRun, isCompatModeEnabled)
    }

    private fun append(type: OutputType, output: String) {
        terminal.appendLine(type, output)
    }

    override fun onCleared() {
        super.onCleared()
        if (Axeron.pingBinder()) stop()
        bgTasks.close()
        terminal.close()
    }
}


