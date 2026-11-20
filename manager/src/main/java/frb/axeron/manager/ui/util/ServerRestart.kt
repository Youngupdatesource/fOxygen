package frb.axeron.manager.ui.util

import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronCommandSession
import frb.axeron.api.core.Starter
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import frb.axeron.manager.service.ServerGuard
import frb.axeron.shell.Axsh

object ServerRestart {

    private const val TAG = "ServerRestart"

    fun command(): String = "{ ${Starter.internalCommand}; } >/dev/null 2>&1 </dev/null"

    fun launch() {
        val argv = AxeronCommandSession.getQuickCmd(command(), true, false)
        Thread({
            val startedViaAxsh = runCatching {
                Axsh.exec(argv, null, null)
            }.isSuccess
            if (startedViaAxsh) return@Thread

            // Fallback 1: old server (e.g. 14800 before API v3) still supports IShizukuService.newProcess
            val startedViaShizuku = runCatching {
                val shizuku = Axeron.getShizukuService() ?: return@runCatching false
                shizuku.newProcess(argv, null, null) != null
            }.getOrDefault(false)
            if (startedViaShizuku) return@Thread

            // Fallback 2: cleanly stop the legacy server and start the new one via ServerGuard
            Log.w(TAG, "legacy server cannot exec restart directly; stopping and restarting via ServerGuard")
            runCatching { Axeron.destroy() }
            runCatching { ServerGuard.restart(axeronApp) }
        }, "ServerRestart").start()
    }
}
