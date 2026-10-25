package frb.axeron.reignite

import android.ddm.DdmHandleAppName
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object Igniter {

    private var VERSION = "v1.2"

    private val AXERONDIR = System.getenv("AXERONDIR")
    private val PLUGINS_DIR = File("$AXERONDIR/plugins")
    private val PLUGINS_UPDATE_DIR = File("$AXERONDIR/plugins_update")

    // From the env that also builds PATH, so links land where PATH looks for them.
    private val AXERONXBIN = File(System.getenv("AXERONXBIN") ?: "$AXERONDIR/xbin")

    /**
     * Env var every process of a plugin's service carries (AXERON_PLUGIN=<id>). Children inherit
     * it even when they leave the service's session, so stopping can still find them.
     */
    private const val MARKER = "AXERON_PLUGIN"

    /** How long a service gets to exit on SIGTERM before what's left is SIGKILLed. */
    private const val STOP_GRACE_MS = 3000L

    /**
     * How long post-fs-data.sh / uninstall.sh may block the igniter, like Magisk's limit for
     * post-fs-data. A script still running after it is left running; the next plugin goes on.
     */
    private const val SCRIPT_TIMEOUT_S = 40L

    /** Output of post-fs-data.sh / uninstall.sh, one file per plugin and script. */
    private val LOG_DIR = File("$AXERONDIR/logs")

    /** Never treated as a plugin's process, even if it carries a leaked marker. */
    private const val SERVER_PROCESS = "axeron_server"

    private var DEBUG = false

    // ===============================
    // ENTRY
    // ===============================
    /** Argument of the server's death watchdog: stop every plugin's processes, nothing else. */
    private const val ARG_STOP_ALL = "--stop-all"

    /**
     * Hands over to the native igniter (libaxeron.so --igniter, server/src/main/cpp/igniter.cpp)
     * when the manager's lib dir has it, and returns its exit code. Same arguments, same stdout
     * (it is inherited), same exit code, so whoever starts this dex (the API's igniteSuspendService,
     * the server's watchdog) needs no change. The native one does a pass over /proc for all plugins,
     * not one per plugin, and does not need the extra second this JVM spends starting up for it.
     *
     * Null when there is no native igniter or it could not be started: the Kotlin code below runs.
     * Once it has started it is never run twice: a non-zero exit is its answer, not a reason to
     * ignite again. AXERONLIB comes from the server's env, the lib dir of the manager the server
     * was started from; after a manager update that dir is gone, so an old dex falls back by itself.
     */
    private fun delegateToNative(args: Array<String>): Int? {
        val lib = System.getenv("AXERONLIB")?.takeIf { it.isNotBlank() } ?: return null
        val exe = File(lib, "libaxeron.so")
        if (!exe.canExecute()) return null
        return try {
            val command = ArrayList<String>(args.size + 2)
            command.add(exe.absolutePath)
            command.add("--igniter")
            command.addAll(args)
            ProcessBuilder(command).inheritIO().start().waitFor()
        } catch (e: Exception) {
            Log.w("Igniter", "native igniter not started, using the Kotlin one", e)
            null
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        delegateToNative(args)?.let { kotlin.system.exitProcess(it) }

        if (args.firstOrNull() == ARG_STOP_ALL) {
            DdmHandleAppName.setAppName("axeron_plugin_stopper", 0)
            stopAllPlugins()
            kotlin.system.exitProcess(0)
        }

        val debug = args.firstOrNull() == "true"
        DEBUG = debug

        DdmHandleAppName.setAppName("axeron_plugin_igniter", 0)
        Log.i("test", "AXERON Plugin Manager ($VERSION)")
        println("AXERON Plugin Manager ($VERSION)")

        // Must be read before cleanUpdateDir(): the folders in plugins_update/ are the only record
        // of which plugins the user ignited by hand (install, uninstall, enable, disable).
        val staged = stagedPlugins()
        cleanUpdateDir()
        mainLoop(staged)
        markBootIgnited()

        println("- Completed")
        kotlin.system.exitProcess(0)
    }

    // ===============================
    // MAIN LOOP
    // ===============================
    private fun mainLoop(staged: Set<String>) {
        val plugins = PLUGINS_DIR.listFiles()?.filter { it.isDirectory }.orEmpty()

        // Plugins the user ignited by hand: a pending update/remove marker, or a staged change
        // (plugins_update/<id>) such as enable/disable.
        val targets = plugins
            .filter { File(it, "update").exists() || File(it, "remove").exists() || it.name in staged }
            .mapTo(HashSet()) { it.name }

        val pending = targets.isNotEmpty()

        // A manual ignite only touches the plugins that asked for it. It is told apart from the
        // first ignite after boot (or after the server took every plugin down), which must start
        // everything, by the boot tag: it is set once an ignite completed and cleared whenever
        // all plugins are stopped, and, being a property, it does not survive a reboot.
        val manual = pending && isBootIgnited()
        if (manual) println("- Manual ignite: ${targets.joinToString(", ")}")

        plugins.forEach { plugin ->
            val name = plugin.name

            if (manual && name !in targets) {
                // Not asked for: leave it exactly as it is, running or not.
                return@forEach
            }
            val bin = File(plugin, "system/bin")
            val fsData = File(plugin, "post-fs-data.sh")
            val sProp = File(plugin, "system.prop")
            val service = File(plugin, "service.sh")
            val uninstall = File(plugin, "uninstall.sh")

            val disable = File(plugin, "disable")
            val remove = File(plugin, "remove")
            val update = File(plugin, "update")

            when {
                remove.exists() -> {
                    println("- Disable and uninstalling $name")
                    stopPlugin(name, bin)
                    uninstallPlugin(name, uninstall, bin)
                    return@forEach
                }

                disable.exists() -> {
                    println("- Disable $name")
                    stopPlugin(name, bin)
                    return@forEach
                }

                update.exists() -> {
                    println("- Updating $name")
                    stopPlugin(name, bin)
                    if (update.delete()) {
                        println(" - Update $name Complete")
                    } else {
                        println(" - Failed to update $name")
                    }
                }

                isServiceRunning(name) -> {
                    println("- $name:${servicePids(name).joinToString(",")} is already running, skip.")
                    markIgnited(name)
                    return@forEach
                }

                // Same rule as 1.1: a plugin ignited since boot is left alone even when it has no
                // process (one-shot service.sh, system.prop / bin only). Only a target (manual
                // ignite) is started again.
                name !in targets && isIgnited(name) -> {
                    println("- $name is already ignited, skip.")
                    return@forEach
                }
            }

            println("- Starting $name")
            startPlugin(name, fsData, sProp, service, bin)
            markIgnited(name)
        }
    }

    // ===============================
    // IGNITED TAG
    // ===============================
    private fun ignitedTag(name: String) = "log.tag.ignited.$name"

    private fun isIgnited(name: String): Boolean = SystemProp.get(ignitedTag(name)) == "1"

    private fun markIgnited(name: String) = SystemProp.set(ignitedTag(name), "1")

    private fun clearIgnited(name: String) = SystemProp.set(ignitedTag(name), "0")

    // ===============================
    // BOOT TAG + STAGED PLUGINS
    // ===============================
    /** Not under "log.tag.ignited.": a plugin could be called "boot". */
    private const val BOOT_TAG = "log.tag.ignite.boot"

    /** An ignite already completed since boot, and no stop-all happened after it. */
    private fun isBootIgnited(): Boolean = SystemProp.get(BOOT_TAG) == "1"

    private fun markBootIgnited() = SystemProp.set(BOOT_TAG, "1")

    private fun clearBootIgnited() = SystemProp.set(BOOT_TAG, "0")

    /**
     * Ids of the plugins with a staged change: plugins_update/<id>/ holding update_install,
     * update_remove, update_enable or update_disable. Same rule as the "Ignite" label in the UI
     * (PluginInfo.update), so an empty leftover folder does not count.
     */
    private fun stagedPlugins(): Set<String> =
        PLUGINS_UPDATE_DIR.listFiles()
            ?.filter { dir -> dir.isDirectory && !dir.list().isNullOrEmpty() }
            ?.mapTo(HashSet()) { it.name }
            .orEmpty()

    // ===============================
    // START PLUGIN (subshell + &)
    // ===============================
    private fun isStandalone(script: File): Boolean {
        if (!script.exists()) return false
        return script.useLines { lines ->
            lines.any { it.trim() == "ASH_STANDALONE=1" }
        }
    }

    private fun startPlugin(
        name: String,
        fsData: File,
        sProp: File,
        service: File,
        bin: File
    ) {
        if (bin.exists()) {
            linkBin(bin)
        }
        if (fsData.exists()) {
            postFsData(name, fsData)
        }
        if (sProp.exists()) {
            applySetprop(name, sProp)
        }
        if (service.exists()) {
            startService(name, service)
        }
    }

    // ===============================
    // SERVICE (setsid + echo $$ + exec)
    // ===============================

    private fun logPipe(tag: String): String {
//        return if (DEBUG)
//            "2>&1 | busybox log -t $tag"
//        else
        return ">/dev/null 2>&1"
    }

    private fun applySetprop(name: String, sProp: File) {
        val tag = "log.tag.props.$name"

        if (SystemProp.get(tag) != "-1") {
            println(" - Applying system.prop of $name")
            SystemProp.set("-f", sProp.absolutePath)
            SystemProp.set(tag, "1")
        }
    }

    private fun postFsData(name: String, fsData: File) {
        val tag = "log.tag.fs.$name"

        if (SystemProp.get(tag) != "1") {
            runScript(name, fsData, "post-fs-data")
            SystemProp.set(tag, "1")
        }
    }

    /**
     * The command for a plugin's blocking script. Each flag is its own argv entry:
     * "sh -o standalone" as one entry is looked up as an applet name. Without ASH_STANDALONE the
     * system's own sh is preferred over busybox's, as everywhere else in the igniter ([Tools]).
     */
    private fun shellCommands(standalone: Boolean, script: File): List<Array<String>> {
        if (standalone) {
            return listOf(arrayOf("busybox", "sh", "-o", "standalone", script.absolutePath))
        }
        return buildList {
            Tools.system("sh")?.let { add(arrayOf(it, script.absolutePath)) }
            add(arrayOf("busybox", "sh", script.absolutePath))
        }
    }

    /**
     * Runs a plugin's blocking script (post-fs-data.sh, uninstall.sh) with its [MARKER], so
     * anything it leaves running can be stopped later. Its output goes to a log file: an unread
     * pipe fills up and hangs the script, and a daemon left holding it dies on SIGPIPE once the
     * igniter exits. stdin is /dev/null. Waits at most [SCRIPT_TIMEOUT_S].
     */
    private fun runScript(name: String, script: File, kind: String) {
        val log = File(LOG_DIR, "$name.$kind.log")
        val out = if (LOG_DIR.isDirectory || LOG_DIR.mkdirs()) log else File("/dev/null")
        // Before it runs: the line stays the current one (and its time runs) while it blocks.
        println(" - Running $kind.sh of $name (up to ${SCRIPT_TIMEOUT_S}s)")
        for (command in shellCommands(isStandalone(script), script)) {
            try {
                val process = ProcessBuilder(*command)
                    .apply { environment()[MARKER] = name }
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectErrorStream(true)
                    .redirectOutput(out)
                    .start()
                if (!process.waitFor(SCRIPT_TIMEOUT_S, TimeUnit.SECONDS)) {
                    println(" - $kind of $name still running after ${SCRIPT_TIMEOUT_S}s, moving on")
                }
                return
            } catch (_: Exception) {
                // This shell could not be started: the next candidate (busybox) is tried.
            }
        }
        println(" - failed to run $kind of $name")
    }

    private fun startService(name: String, service: File) {
        val tag = "axeron.plugin.$name"
        val log = logPipe(tag)
        val standalone = isStandalone(service)

        val shell = Tools.word("sh")
        val setsid = Tools.word("setsid")
        val innerShell = if (standalone) "busybox sh -o standalone" else shell
        val execLine = "exec $innerShell \"${service.absolutePath}\""

        // The plugin id reaches the shell through the env, never pasted into the script text.
        // stdin is /dev/null so nothing waits on a pipe the igniter closes when it exits.
        val script = $$"""
            $$setsid $$shell -c '
              $$execLine
            ' $$log </dev/null &
            pid=$!
            resetprop "log.tag.service.$$$MARKER" "$pid"
        """.trimIndent()

        println(" - startService $name")
        launchShell(script, name)
    }

    /** Runs [script] in a shell that carries the plugin's [MARKER] in its env. */
    private fun launchShell(script: String, name: String) {
        val candidates = buildList {
            Tools.system("sh")?.let { add(arrayOf(it, "-c", script)) }
            add(arrayOf("busybox", "sh", "-c", script))
        }
        for (command in candidates) {
            try {
                ProcessBuilder(*command)
                    .apply { environment()[MARKER] = name }
                    .start()
                return
            } catch (_: Exception) {
                // This shell could not be started: the next candidate (busybox) is tried.
            }
        }
        println("ERROR: startService $name")
    }

    // ===============================
    // STOP / UNINSTALL
    // ===============================
    private fun stopPlugin(name: String, bin: File) {
        clearIgnited(name)
        SystemProp.set("log.tag.fs.$name", "0")
        SystemProp.set("log.tag.props.$name", "0")

        stopService(name)
        SystemProp.set("log.tag.service.$name", "-1")

        unlinkBin(bin)
    }

    /**
     * SIGTERM to every process of the service, a grace period to exit, then SIGKILL to whatever
     * is left, re-scanned so children forked meanwhile are caught too.
     */
    private fun stopService(name: String) = stopProcesses("service $name") { servicePids(name) }

    /**
     * Stops every plugin's processes at once; the server's death watchdog runs this. A new
     * server may already be starting plugins meanwhile (a restart), so it is confined to what
     * was running when it began and what descends from or joined those, never fresh services.
     */
    private fun stopAllPlugins() {
        stopProcesses("all plugins", confine = true) { allPluginPids() }
        // Every plugin goes back to "not ignited", also the ones without a process, so the next
        // ignite starts them all again.
        PLUGINS_DIR.list().orEmpty().forEach { clearIgnited(it) }
        clearBootIgnited()
    }

    private fun stopProcesses(label: String, confine: Boolean = false, scan: () -> List<Int>) {
        var pids = scan()
        if (pids.isEmpty()) return
        val known = HashSet(pids)
        println(" - stopping $label: ${pids.joinToString(" ")}")
        pids.forEach { signal(it, OsConstants.SIGTERM) }

        val deadline = System.currentTimeMillis() + STOP_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            pids = if (confine) confined(scan(), known) else scan()
            if (pids.isEmpty()) return
        }

        println(" - killing $label: ${pids.joinToString(" ")}")
        pids.forEach { signal(it, OsConstants.SIGKILL) }
    }

    /**
     * The part of [current] that is in [known], or is a child or session/group member of one of
     * them, repeated until nothing new joins; [known] grows with what is kept.
     */
    private fun confined(current: List<Int>, known: MutableSet<Int>): List<Int> {
        val keep = current.filterTo(HashSet()) { it in known }
        do {
            var grew = false
            for (pid in current) {
                if (pid in keep) continue
                val stat = readStat(pid) ?: continue
                if (stat.ppid in known || stat.session in known || stat.pgrp in known) {
                    keep.add(pid)
                    known.add(pid)
                    grew = true
                }
            }
        } while (grew)
        return keep.toList()
    }

    private fun signal(pid: Int, sig: Int) {
        try {
            Os.kill(pid, sig)
        } catch (_: ErrnoException) {
            // Already gone, or not ours to signal.
        }
    }

    /**
     * Live processes of a plugin: those carrying its [MARKER], plus every member of a session or
     * process group led by one of them. The second part catches children that cleared their env
     * but stayed with their daemon (e.g. crond's jobs, which start with a clean env).
     *
     * The igniter, its ancestors and the server are never included, nor taken as leaders, even
     * with a leaked marker (a plugin that restarted AxManager or ran the igniter itself).
     */
    private fun servicePids(name: String): List<Int> =
        pluginPids(legacyLeaders(name)) { it == "$MARKER=$name" }

    /** Processes of any plugin, by the same rule as [servicePids]. */
    private fun allPluginPids(): List<Int> {
        val legacy = PLUGINS_DIR.list().orEmpty().flatMap { legacyLeaders(it) }
        return pluginPids(legacy) { it.startsWith("$MARKER=") }
    }

    /**
     * A service started by an igniter older than the marker carries none; all that names it is
     * the pid in its `log.tag.service.<name>` prop, the leader of the session `setsid` made for
     * it. Without this, such a service would count as not running after an update and be
     * started a second time. The prop outlives its process and pids get reused, so the pid only
     * counts while it is alive, still leads its session, and runs this plugin's own files.
     */
    private fun legacyLeaders(name: String): List<Int> {
        val pid = legacyPids.getOrPut(name) {
            SystemProp.get("log.tag.service.$name").trim().toIntOrNull() ?: -1
        }
        if (pid <= 1) return emptyList()
        val stat = readStat(pid) ?: return emptyList()
        if (stat.state == 'Z' || stat.session != pid) return emptyList()
        val pluginDir = "${PLUGINS_DIR.absolutePath}/$name/"
        return if (cmdline(pid).any { it.startsWith(pluginDir) }) listOf(pid) else emptyList()
    }

    /** The prop is read once per plugin: a stop scans every 100 ms, and each read runs resetprop. */
    private val legacyPids = HashMap<String, Int>()

    /** [servicePids] with [isMarker] deciding which env entries count as a plugin's marker. */
    private fun pluginPids(
        extraLeaders: List<Int> = emptyList(),
        isMarker: (String) -> Boolean
    ): List<Int> {
        val protected = selfAndAncestors()

        val live = File("/proc").list()
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it !in protected }
            ?.mapNotNull { pid -> readStat(pid)?.let { pid to it } }
            ?.filter { (_, stat) -> stat.state != 'Z' } // already dead, waiting to be reaped
            .orEmpty()

        val marked = live
            .filter { (pid, _) -> hasMarker(pid, isMarker) && !isServer(pid) }
            .mapTo(HashSet()) { it.first }
        extraLeaders.filterTo(marked) { it !in protected && !isServer(it) }

        return live
            .filter { (pid, stat) -> pid in marked || stat.session in marked || stat.pgrp in marked }
            .map { it.first }
    }

    /** The igniter and every process above it: stopping a plugin must never kill its caller. */
    private fun selfAndAncestors(): Set<Int> {
        val chain = HashSet<Int>()
        var pid = Os.getpid()
        while (pid > 1 && chain.add(pid)) {
            pid = readStat(pid)?.ppid ?: break
        }
        return chain
    }

    private fun cmdline(pid: Int): List<String> = try {
        File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000')
    } catch (_: Exception) {
        emptyList()
    }

    private fun isServer(pid: Int): Boolean = cmdline(pid).firstOrNull() == SERVER_PROCESS

    private class ProcStat(val state: Char, val ppid: Int, val pgrp: Int, val session: Int)

    /** Fields of /proc/<pid>/stat; the name field can hold spaces and ')'. */
    private fun readStat(pid: Int): ProcStat? = try {
        val stat = File("/proc/$pid/stat").readText()
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        // After the name: state, ppid, pgrp, session, ...
        ProcStat(fields[0][0], fields[1].toInt(), fields[2].toInt(), fields[3].toInt())
    } catch (_: Exception) {
        null
    }

    /** Whether an env entry of the process passes [isMarker]; unreadable envs (other uids) count as no. */
    private fun hasMarker(pid: Int, isMarker: (String) -> Boolean): Boolean = try {
        File("/proc/$pid/environ").readBytes()
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .any(isMarker)
    } catch (_: Exception) {
        false
    }

    private fun uninstallPlugin(name: String, uninstall: File, bin: File) {
        if (uninstall.exists()) {
            runScript(name, uninstall, "uninstall")
            // uninstall.sh may itself have started something; it goes with the plugin.
            stopService(name)
        }
        unlinkBin(bin)
        File("$AXERONDIR/plugins/$name").deleteRecursively()
    }

    // ===============================
    // LINK BIN
    // ===============================
    private fun linkBin(bin: File) {
        if (!bin.isDirectory) return

        AXERONXBIN.mkdirs()
        bin.listFiles()?.forEach { src ->
            val dst = File(AXERONXBIN, src.name)

            try {
                if (dst.exists()) {
                    if (!dst.delete()) {
                        println(" - Failed to remove: ${dst.absolutePath}")
                    }
                }
                // buat symlink baru
                Os.symlink(src.absolutePath, dst.absolutePath)
                println(" - Linked : ${dst.absolutePath}")
            } catch (_: ErrnoException) {
                println(" - Failed Linking : ${src.absolutePath}")
            }
        }
    }

    private fun unlinkBin(bin: File) {
        if (!bin.exists()) return
        bin.listFiles()?.forEach { src ->
            val dst = File(AXERONXBIN, src.name)
            if (!dst.exists()) return@forEach
            if (Os.readlink(dst.absolutePath) == src.absolutePath) {
                println(" - Unlinked : ${dst.absolutePath}")
                File(AXERONXBIN, src.name).delete()
            }
        }
    }

    // ===============================
    // UTIL
    // ===============================
    private fun cleanUpdateDir() {
        PLUGINS_UPDATE_DIR.listFiles()?.forEach {
            if (it.isDirectory) it.deleteRecursively()
        }
    }

    /** Running means a live process of the service exists, so a crashed one is started again. */
    private fun isServiceRunning(name: String): Boolean = servicePids(name).isNotEmpty()

    private object Tools {
        private val SYSTEM_DIRS = arrayOf("/system/bin", "/system/xbin", "/vendor/bin")
        private val cache = HashMap<String, String?>()

        @Synchronized
        fun system(applet: String): String? {
            if (cache.containsKey(applet)) return cache[applet]
            val found = SYSTEM_DIRS.asSequence()
                .map { File(it, applet) }
                .firstOrNull { it.canExecute() && !isBusybox(it) }
                ?.absolutePath
            cache[applet] = found
            return found
        }

        fun word(applet: String): String = system(applet) ?: "busybox $applet"

        private fun isBusybox(file: File): Boolean = try {
            file.canonicalFile.name.startsWith("busybox")
        } catch (_: Exception) {
            false
        }
    }
}
